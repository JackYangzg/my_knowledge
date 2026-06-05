package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.viewmodel.ImportCenterRow
import com.my.knowledge.viewmodel.ImportCenterViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import androidx.compose.material3.MaterialTheme
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.Palette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun ImportCenterScreen(
    viewModel: ImportCenterViewModel,
    onBack: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val rows by viewModel.rows.collectAsState()
    var deleteSourceId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bgPage)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp, bottom = 12.dp)
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.brand)
                Spacer(modifier = Modifier.size(4.dp))
                Text(stringResource(R.string.auto_11d02415), fontSize = 14.sp, color = palette.brand)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.auto_2ff3cc93), style = MaterialTheme.typography.displayLarge, color = palette.textPrimary)
            Text(stringResource(R.string.auto_00b4a995), style = MaterialTheme.typography.labelLarge, color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (rows.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.auto_bb9db662), color = palette.textSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                items(rows) { row ->
                    ImportSourceCard(
                        row = row,
                        onRetry = { viewModel.retrySource(row.source.id) },
                        onCancel = { row.latestTask?.let { viewModel.cancelTask(it.id) } },
                        onDelete = { deleteSourceId = row.source.id }
                    )
                }
            }
        }
    }

    if (deleteSourceId != null) {
        AlertDialog(
            onDismissRequest = { deleteSourceId = null },
            title = { Text(stringResource(R.string.auto_ecafc815)) },
            text = { Text(stringResource(R.string.auto_79bc3afb)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteSourceId?.let(viewModel::deleteSource)
                        deleteSourceId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.semanticError)
                ) { Text(stringResource(R.string.auto_3755f56f)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSourceId = null }) { Text(stringResource(R.string.auto_4d0b4688)) }
            }
        )
    }
}

@Composable
private fun ImportSourceCard(
    row: ImportCenterRow,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val task = row.latestTask
    Surface(
        shape = RoundedCornerShape(spacing.md),
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sourceIcon(row.source), contentDescription = null, tint = statusColor(palette, row.source.status), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.source.title, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
                    Text("${row.source.sourceType} · ${row.source.mimeType ?: "unknown"} · ${formatTime(row.source.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                }
                StatusPill(row.source.status)
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (task != null) {
                Text("${task.taskType} · ${task.currentStep ?: task.status}", style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = statusColor(palette, task.status),
                    trackColor = palette.brandSubtle
                )
            } else {
                Text(stringResource(R.string.auto_147d229d), style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (task?.status == "failed" || task?.status == "canceled") {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.review_queue_retry_cd), tint = palette.brand)
                    }
                }
                if (task?.status == "pending" || task?.status == "running") {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Cancel, contentDescription = stringResource(R.string.auto_4d0b4688), tint = palette.semanticWarning)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.auto_ecafc815), tint = palette.semanticError)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(color = statusColor(palette, status).copy(alpha = 0.12f), shape = CircleShape) {
        Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor(palette, status), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

private fun sourceIcon(source: SourceDocumentEntity): ImageVector = when (source.sourceType) {
    "image" -> Icons.Default.Image
    "audio" -> Icons.Default.Mic
    "pdf" -> Icons.Default.PictureAsPdf
    "docx", "file" -> Icons.Default.InsertDriveFile
    else -> Icons.Default.Description
}

private fun statusColor(palette: Palette, status: String): Color = when (status) {
    "running", "parsing", "analyzing" -> palette.brand
    "pending", "imported", "parsed", "pending_network" -> Color(0xFFF59E0B)
    "failed" -> palette.semanticError
    "canceled" -> palette.semanticWarning
    "generated", "success" -> Color(0xFF0B816F)
    else -> palette.textSecondary
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
