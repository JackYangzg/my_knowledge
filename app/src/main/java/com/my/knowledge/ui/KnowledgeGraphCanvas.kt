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

private const val G6_CDN_URL = "https://unpkg.com/@antv/g6@5/dist/g6.min.js"

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
                            "https://antv-g6.local/",
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
          showError('G6 加载失败，请检查网络。');
          return;
        }
        const container = document.getElementById('container');
        const width = container.clientWidth || window.innerWidth;
        const height = container.clientHeight || window.innerHeight;
        const nodes = (data.nodes || []).map((node) => ({
          id: node.id,
          data: node,
          style: {
            size: node.size,
            fill: node.color,
            stroke: '#FFFFFF',
            lineWidth: 2,
            labelText: node.label,
            labelFill: '#334155',
            labelFontSize: 12,
            labelPlacement: 'bottom',
            labelMaxWidth: 120
          }
        }));
        const edges = (data.edges || []).map((edge) => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          data: edge,
          style: {
            stroke: edge.color,
            lineWidth: edge.relationType && edge.relationType.indexOf('analysis:') === 0 ? 1.8 : 1.2,
            opacity: Math.max(0.25, Math.min(0.85, (edge.confidence || 0.5) * 0.7 + 0.15))
          }
        }));
        if (graph) graph.destroy();
        graph = new window.G6.Graph({
          container,
          width,
          height,
          data: { nodes, edges },
          autoFit: 'view',
          padding: 48,
          layout: {
            type: 'force',
            preventOverlap: true,
            nodeSize: (d) => d.data && d.data.size ? d.data.size : 28,
            linkDistance: 120
          },
          behaviors: [
            'drag-canvas',
            'zoom-canvas',
            'drag-element'
          ],
          node: {
            state: {
              selected: { stroke: '#0F172A', lineWidth: 3 },
              hover: { stroke: '#0F172A', lineWidth: 2 }
            }
          },
          edge: {
            state: {
              hover: { lineWidth: 2.4, opacity: 0.9 }
            }
          }
        });
        graph.on('node:click', (event) => {
          const id = event && event.target && event.target.id;
          if (id && window.KnowledgeGraphBridge) {
            window.KnowledgeGraphBridge.onNodeClick(String(id));
          }
        });
        graph.on('node:pointerenter', (event) => {
          if (event && event.target) graph.setElementState(event.target.id, 'hover');
        });
        graph.on('node:pointerleave', (event) => {
          if (event && event.target) graph.setElementState(event.target.id, []);
        });
        graph.render();
        document.getElementById('loading').style.display = 'none';
      } catch (err) {
        showError('图谱渲染失败：' + (err && err.message ? err.message : err));
      }
    }

    window.addEventListener('resize', () => {
      if (!graph) return;
      const container = document.getElementById('container');
      graph.resize(container.clientWidth || window.innerWidth, container.clientHeight || window.innerHeight);
      graph.fitView();
    });
  </script>
  <script src="$G6_CDN_URL" onload="g6Ready=true; if (pendingData) renderGraph(pendingData);" onerror="showError('G6 脚本加载失败，请检查网络。')"></script>
</body>
</html>
""".trimIndent()
