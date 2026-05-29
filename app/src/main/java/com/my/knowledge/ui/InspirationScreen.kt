package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.viewmodel.NoteEditorViewModel

@Composable
fun InspirationScreen(viewModel: NoteEditorViewModel) {
    val mode = viewModel.mode
    val title = viewModel.title
    val content = viewModel.content
    val saveStatus by viewModel.saveStatus.collectAsState()
    
    var showMoreMenu by remember { mutableStateOf(false) }
    var selectedLibrary by remember { mutableStateOf("未归类") }
    var showLibraryPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 48.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("灵感", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                IconButton(
                    onClick = {
                        viewModel.createNewNote()
                        selectedLibrary = "未归类"
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFFF7FBFF), CircleShape)
                ) {
                    Icon(Icons.Default.Done, contentDescription = "保存并完成", tint = Color(0xFF147EC5))
                }
            }

            // Markdown Tab Bar
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .background(Color(0xFFEFF7FF), RoundedCornerShape(999.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarkdownModeBtn("编辑", mode == "edit") { viewModel.toggleMode() }
                MarkdownModeBtn("查看", mode == "preview") { viewModel.toggleMode() }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = when(saveStatus) {
                        "saving" -> "● 正在保存..."
                        "saved" -> "● 已自动保存"
                        else -> "● 待保存"
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF6AA8D0),
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

            HorizontalDivider(color = Color(0xFFF3F4F6), modifier = Modifier.padding(top = 8.dp))

            // Editor Area
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    if (mode == "edit") {
                        TextField(
                            value = title,
                            onValueChange = { viewModel.title = it },
                            placeholder = { Text("标题", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4D4D4)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextField(
                            value = content,
                            onValueChange = { viewModel.content = it },
                            placeholder = { Text("先记下来，不必马上整理。", fontSize = 16.sp, color = Color(0xFFD4D4D4)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, color = Color(0xFF262626)),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    } else {
                        // Preview Mode
                        if (title.isNotEmpty()) {
                            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (content.isNotEmpty()) {
                            Text(content, fontSize = 16.sp, lineHeight = 28.sp, color = Color(0xFF262626))
                        } else if (title.isEmpty()) {
                            Text("无内容", fontSize = 16.sp, color = Color(0xFFD4D4D4))
                        }
                    }
                    Spacer(modifier = Modifier.height(100.dp))
                }
                
                // Floating Action Buttons
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = { },
                        containerColor = Color.White,
                        contentColor = Color(0xFF147EC5),
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp).shadow(4.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                    }
                    FloatingActionButton(
                        onClick = { showMoreMenu = true },
                        containerColor = Color(0xFF147EC5),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp).shadow(12.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = null)
                    }
                }
            }
        }

        // More Menu Overlay
        if (showMoreMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showMoreMenu = false }
                    )
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 82.dp)
                        .fillMaxWidth()
                        .shadow(22.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFECECEC))
                ) {
                    Column {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("更多", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                            IconButton(
                                onClick = { showMoreMenu = false },
                                modifier = Modifier.size(28.dp).background(Color(0xFFF5F5F5), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF737373))
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF3F4F6))
                        MoreMenuItem("添加图片到文档", "🖼")
                        MoreMenuItem("添加附件到文档", "📎")
                        
                        // Library Picker Item
                        Box(modifier = Modifier.fillMaxWidth()) {
                            MoreMenuItem(
                                label = "保存到", 
                                rightText = selectedLibrary, 
                                isStrong = true,
                                onClick = { showLibraryPicker = !showLibraryPicker }
                            )
                            DropdownMenu(
                                expanded = showLibraryPicker,
                                onDismissRequest = { showLibraryPicker = false },
                                modifier = Modifier.fillMaxWidth(0.8f).background(Color.White)
                            ) {
                                KnowledgeManager.libraries.forEach { lib ->
                                    DropdownMenuItem(
                                        text = { Text(lib.name) },
                                        onClick = {
                                            selectedLibrary = lib.name
                                            showLibraryPicker = false
                                        }
                                    )
                                }
                            }
                        }

                        MoreMenuItem("添加标签", "›")
                        MoreMenuItem("移动到知识库", "›")
                        MoreMenuItem("标记需要整理", "›")
                        MoreMenuItem("删除", "›", isDestructive = true)
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownModeBtn(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) Color.White else Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        shadowElevation = if (active) 2.dp else 0.dp
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (active) Color(0xFF0F172A) else Color(0xFF737373),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun MoreMenuItem(
    label: String, 
    rightText: String, 
    isStrong: Boolean = false, 
    isDestructive: Boolean = false,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.White
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    color = if (isDestructive) Color(0xFFDC2626) else Color(0xFF111827)
                )
                Text(
                    rightText,
                    fontSize = 14.sp,
                    fontWeight = if (isStrong) FontWeight.Bold else FontWeight.Normal,
                    color = if (isDestructive) Color(0xFFFCA5A5) else if (isStrong) Color(0xFF111827) else Color(0xFFD4D4D4)
                )
            }
            HorizontalDivider(color = Color(0xFFF5F5F5))
        }
    }
}
