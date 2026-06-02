package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.viewmodel.KnowledgeEditorViewModel

/**
 * Markdown editor dedicated to existing knowledge items. Distinct from
 * the inspiration note editor (`InspirationScreen`):
 *
 * - opens when the user taps "编辑" on a knowledge item;
 * - the title bar shows **「编辑 <title>」** so the user always knows
 *   they are editing an existing knowledge entry, not a fresh note;
 * - saves back into the same `knowledge_item` row in place
 *   (`updateItem` — no new source / no re-parse / no new item), and
 *   rebuilds the fragment table so RAG search picks up the new
 *   content immediately.
 */
@Composable
fun KnowledgeEditorScreen(
    itemId: String,
    viewModel: KnowledgeEditorViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit = {}
) {
    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    val item by viewModel.item.collectAsState()
    val title by viewModel.title.collectAsState()
    val content by viewModel.content.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val reingestStatus by viewModel.reingestStatus.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showSaveConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .imePadding()
    ) {
        // Header — the user explicitly asked for "编辑 [title]" in the
        // page title. The IconButton "←" returns to the knowledge viewer
        // without saving; the "保存" button on the right commits.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp)
                .padding(top = 48.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color(0xFF147EC5)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = Color(0xFF147EC5),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "编辑 ${item?.title ?: title.ifBlank { "..." }}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A),
                    maxLines = 1
                )
            }
            TextButton(
                onClick = { showSaveConfirm = true },
                enabled = !isSaving && item != null
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF147EC5),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("保存", color = Color(0xFF147EC5), fontWeight = FontWeight.SemiBold)
            }
        }

        if (item == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF147EC5))
            }
        } else {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "标题",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = viewModel::setTitle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF147EC5),
                        unfocusedBorderColor = Color(0xFFDBEEFF)
                    )
                )

                Text(
                    text = "正文 (Markdown)",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = viewModel::setContent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFF0F172A)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF147EC5),
                        unfocusedBorderColor = Color(0xFFDBEEFF)
                    )
                )

                Surface(
                    color = Color(0xFFFFF7ED),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "提示:支持标准 Markdown 语法(标题、列表、引用、链接)。" +
                            "保存后会立即生效,知识图谱和检索都会更新。",
                        fontSize = 11.sp,
                        color = Color(0xFF9A6A1F),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }

    if (showSaveConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = { Text("保存修改？", fontWeight = FontWeight.Bold) },
            text = {
                Text("将覆盖当前知识条目的内容,图谱和检索会同步更新。原内容会被永久替换。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSaveConfirm = false
                    viewModel.save {
                        onSaved()
                    }
                }) {
                    Text("保存", color = Color(0xFF147EC5), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (isSaving) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF147EC5))
        }
    }

    // Toast the re-ingest status once it lands. The status string lives
    // in the view-model until consumeSaveCompleted() is called, so a
    // recomposition after onSaved → popBackStack won't lose it.
    val savedStatus = reingestStatus
    androidx.compose.runtime.LaunchedEffect(savedStatus) {
        if (savedStatus != null) {
            android.widget.Toast.makeText(
                context,
                savedStatus,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
