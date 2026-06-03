package com.my.knowledge.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

// G6 v4 ships a UMD bundle; G6 v5 is ESM-only and cannot be loaded via
// <script src>. Bundling G6 v4 into the APK also means the graph works
// offline — the previous CDN approach would silently fail in regions
// where unpkg is slow or blocked, leaving the page blank with no error
// hint. The file lives at app/src/main/assets/graph/g6.min.js (~1.8 MB).
private const val G6_ASSET_URL = "file:///android_asset/graph/g6.min.js"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ForceDirectedGraph(
    entities: List<KnowledgeEntityEntity>,
    relations: List<KnowledgeRelationEntity>,
    modifier: Modifier = Modifier,
    onNodeClick: (KnowledgeEntityEntity) -> Unit = {}
) {
    val latestOnNodeClick by rememberUpdatedState(onNodeClick)
    val entityById by rememberUpdatedState(entities.associateBy { it.id })
    val graphJson = remember(entities, relations) { buildG6GraphJson(entities, relations) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFAFBFD))
    ) {
        if (entities.isEmpty()) {
            Text(
                "暂无图谱数据 — 导入知识后会自动生成实体与关系。",
                fontSize = 13.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.rgb(250, 251, 253))
                        // Mixed content would only matter if we kept the
                        // CDN, but we still default to safe behaviour in
                        // case a future maintainer swaps the source.
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        addJavascriptInterface(
                            GraphBridge { nodeId ->
                                entityById[nodeId]?.let { latestOnNodeClick(it) }
                            },
                            "KnowledgeGraphBridge"
                        )
                        loadDataWithBaseURL(
                            // The base URL must share an origin with the
                            // G6 script for the file:// asset to load
                            // under the same-origin policy. file://...
                            // matches file:///android_asset/... so this
                            // works without mixed-content config.
                            "file:///android_asset/graph/",
                            graphHtml(),
                            "text/html",
                            "UTF-8",
                            null
                        )
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                    view.evaluateJavascript("window.setGraphData($graphJson);", null)
                }
            )
        }
    }

    LaunchedEffect(graphJson, webView) {
        webView?.evaluateJavascript("window.setGraphData($graphJson);", null)
    }

    DisposableEffect(webView) {
        onDispose {
            webView?.removeJavascriptInterface("KnowledgeGraphBridge")
            webView?.destroy()
            webView = null
        }
    }
}

private class GraphBridge(
    private val onNodeClick: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onNodeClick(nodeId: String) {
        mainHandler.post { onNodeClick(nodeId) }
    }
}

private fun buildG6GraphJson(
    entities: List<KnowledgeEntityEntity>,
    relations: List<KnowledgeRelationEntity>
): String {
    val nodes = JSONArray()
    entities.forEach { entity ->
        val color = nodeColor(entity.type)
        val size = 22 + min(entity.weight, 30f).toInt()
        nodes.put(
            JSONObject()
                .put("id", entity.id)
                .put("label", entity.name)
                .put("type", entity.type)
                .put("weight", entity.weight)
                .put("size", size.coerceIn(20, 48))
                .put("color", color)
        )
    }

    val validNodeIds = entities.map { it.id }.toSet()
    val edges = JSONArray()
    relations
        .filter { it.fromEntityId in validNodeIds && it.toEntityId in validNodeIds && it.fromEntityId != it.toEntityId }
        .forEach { relation ->
            edges.put(
                JSONObject()
                    .put("id", relation.id)
                    .put("source", relation.fromEntityId)
                    .put("target", relation.toEntityId)
                    .put("relationType", relation.relationType)
                    .put("confidence", relation.confidence)
                    .put("color", edgeColor(relation))
            )
        }

    return JSONObject()
        .put("nodes", nodes)
        .put("edges", edges)
        .toString()
}

private fun nodeColor(type: String): String = when (type.lowercase()) {
    "entity" -> "#147EC5"
    "concept" -> "#22C55E"
    "source" -> "#F59E0B"
    "person" -> "#DB2777"
    "organization", "org" -> "#7C3AED"
    "location", "place" -> "#0891B2"
    "event" -> "#EA580C"
    else -> "#6366F1"
}

private fun edgeColor(relation: KnowledgeRelationEntity): String = when {
    relation.relationType.startsWith("analysis:") -> "#EF4444"
    relation.relationType == "source_overlap" -> "#A855F7"
    else -> "#94A3B8"
}

private fun graphHtml(): String = """
<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
  <style>
    html, body, #container {
      width: 100%;
      height: 100%;
      margin: 0;
      overflow: hidden;
      background: #FAFBFD;
      touch-action: none;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    #loading, #error, #legend {
      position: absolute;
      z-index: 10;
      border-radius: 8px;
      background: rgba(255,255,255,.92);
      box-shadow: 0 1px 5px rgba(15,23,42,.08);
      color: #334155;
      font-size: 12px;
    }
    #loading {
      left: 50%;
      top: 50%;
      transform: translate(-50%, -50%);
      padding: 10px 14px;
    }
    #error {
      display: none;
      left: 14px;
      right: 14px;
      bottom: 14px;
      padding: 10px 12px;
      color: #B91C1C;
      max-height: 40%;
      overflow: auto;
    }
    #legend {
      left: 12px;
      top: 12px;
      padding: 10px 12px;
      line-height: 1.75;
    }
    .dot {
      display: inline-block;
      width: 9px;
      height: 9px;
      border-radius: 50%;
      margin-right: 6px;
    }
  </style>
</head>
<body>
  <div id="container"></div>
  <div id="loading">正在加载图谱...</div>
  <div id="legend">
    <div><span class="dot" style="background:#147EC5"></span>实体</div>
    <div><span class="dot" style="background:#22C55E"></span>概念</div>
    <div><span class="dot" style="background:#F59E0B"></span>来源</div>
    <div><span class="dot" style="background:#EF4444"></span>分析关系</div>
    <div><span class="dot" style="background:#A855F7"></span>同源关联</div>
  </div>
  <div id="error"></div>
  <script>
    // ---- G6 v4.x API (asset is 4.8.23 UMD bundle) ----------------
    //
    // 之前这段是 G6 v5 的写法:`new G6.Graph({ data, behaviors, node, edge })`,
    // v4 的 Graph 构造器拿到这些键会全部忽略——v4 的标准流程是
    //   new G6.Graph({ container, width, height, modes, layout,
    //                  defaultNode, defaultEdge, nodeStateStyles,
    //                  fitView, fitViewPadding })
    //   graph.data({ nodes, edges });
    //   graph.render();
    // 同时节点/边的样式通过 defaultNode/defaultEdge 的 style 回调函数
    // 写,不嵌套 data/style。状态用 setItemState 而非 setElementState。
    // 这就是为什么图谱"一片白板"——graph 构造了、render() 跑了,但
    // data 从未注入,画布始终空。
    let graph = null;
    let pendingData = null;
    let g6Ready = false;

    window.setGraphData = function(data) {
      pendingData = data;
      if (g6Ready) renderGraph(data);
    };

    function showError(message) {
      const el = document.getElementById('error');
      el.style.display = 'block';
      el.textContent = message;
      document.getElementById('loading').style.display = 'none';
    }

    function renderGraph(data) {
      try {
        if (!window.G6 || !window.G6.Graph) {
          showError('G6 加载失败，请检查 assets/graph/g6.min.js 是否存在。');
          return;
        }
        const container = document.getElementById('container');
        // 首次进入页面时 WebView 还没完成 layout,clientWidth/clientHeight
        // 可能为 0——先用窗口尺寸兜底,等到 resize 时再 fitView。
        const width = container.clientWidth || window.innerWidth || 360;
        const height = container.clientHeight || window.innerHeight || 640;

        // v4 node model: 直接把 node.id / size / color / label 平铺在节点上,
        // defaultNode.style 回调函数读这些字段填到 G6 内部 model。
        const nodes = (data.nodes || []).map((node) => ({
          id: node.id,
          size: node.size,
          color: node.color,
          label: node.label,
          type: node.type,
          weight: node.weight
        }));
        const edges = (data.edges || []).map((edge) => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          color: edge.color,
          relationType: edge.relationType,
          confidence: edge.confidence,
          // 把"分析关系/同源关联"是否加粗的判定挪到 G6 渲染时:
          // edge.lineWidth 不在 model 上,用 relationType 在 defaultEdge
          // 回调里判断即可,这里不再带冗余字段。
        }));

        if (graph) { graph.destroy(); graph = null; }
        graph = new window.G6.Graph({
          container,
          width,
          height,
          fitView: true,
          fitViewPadding: 48,
          animate: false,
          // v4 用 modes 描述交互(原 v5 的 behaviors 在 v4 里没有这个名字)
          modes: {
            default: ['drag-canvas', 'zoom-canvas', 'drag-node']
          },
          layout: {
            type: 'force',
            preventOverlap: true,
            linkDistance: 120,
            // v4 nodeSize 接受数字或 (node) => number——传函数时回调收到
            // G6 内部 model(已含我们放进去的 size 字段)
            nodeSize: (n) => (n && n.size ? n.size : 30)
          },
          defaultNode: {
            type: 'circle',
            size: (n) => (n && n.size ? n.size : 30),
            style: {
              fill: (n) => (n && n.color ? n.color : '#147EC5'),
              stroke: '#FFFFFF',
              lineWidth: 2,
              // v4 把 label 配置塞在 style.label 里(value / fill / fontSize /
              // position 字段名与 v5 不同,position 用 'bottom' / 'top' / 'center')
              label: {
                value: (n) => (n && n.label ? n.label : ''),
                fill: '#334155',
                fontSize: 12,
                position: 'bottom'
              }
            }
          },
          defaultEdge: {
            type: 'line',
            style: {
              stroke: (e) => (e && e.color ? e.color : '#94A3B8'),
              lineWidth: (e) => (e && e.relationType && e.relationType.indexOf('analysis:') === 0 ? 1.8 : 1.2),
              opacity: (e) => {
                const c = (e && typeof e.confidence === 'number') ? e.confidence : 0.5;
                return Math.max(0.25, Math.min(0.85, c * 0.7 + 0.15));
              },
              endArrow: true
            }
          },
          // v4 节点状态样式用 nodeStateStyles(全局),通过 setItemState(id,
          // 'hover', true) 切换。原代码用了 v5 的 `node: { state: {...} }` +
          // setElementState,v4 完全不识别。
          nodeStateStyles: {
            hover: { stroke: '#0F172A', lineWidth: 3 },
            selected: { stroke: '#0F172A', lineWidth: 3 }
          }
        });

        // v4 标准三步:构造 → data → render
        graph.data({ nodes, edges });
        graph.render();

        // v4 事件对象:event.target 通常是节点 ID string,
        // event.item 是节点 G6 model(instance)——两种都接受。
        graph.on('node:click', (event) => {
          const id = event && event.target ? String(event.target) : null;
          if (id && window.KnowledgeGraphBridge) {
            window.KnowledgeGraphBridge.onNodeClick(id);
          }
        });
        graph.on('node:mouseenter', (event) => {
          const id = event && event.target ? String(event.target) : null;
          if (id) graph.setItemState(id, 'hover', true);
        });
        graph.on('node:mouseleave', (event) => {
          const id = event && event.target ? String(event.target) : null;
          if (id) graph.setItemState(id, 'hover', false);
        });

        document.getElementById('loading').style.display = 'none';
      } catch (err) {
        showError('图谱渲染失败：' + (err && err.message ? err.message : err));
      }
    }

    window.addEventListener('resize', () => {
      if (!graph) return;
      const container = document.getElementById('container');
      const w = container.clientWidth || window.innerWidth;
      const h = container.clientHeight || window.innerHeight;
      try {
        graph.changeSize(w, h);
        graph.fitView(48);
      } catch (_) {
        // ignore resize on partially-initialised graph
      }
    });
  </script>
  <script src="$G6_ASSET_URL" onload="g6Ready=true; if (pendingData) renderGraph(pendingData);" onerror="showError('G6 脚本加载失败，请重新安装 App。')"></script>
</body>
</html>
""".trimIndent()
