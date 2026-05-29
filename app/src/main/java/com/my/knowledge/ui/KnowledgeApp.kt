package com.my.knowledge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.viewmodel.KnowledgeHomeViewModel
import com.my.knowledge.viewmodel.KnowledgeItemListViewModel
import com.my.knowledge.viewmodel.KnowledgeManageViewModel
import com.my.knowledge.viewmodel.NoteEditorViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi

enum class Tab(val id: String, val label: String, val icon: ImageVector) {
    KNOWLEDGE("knowledge", "知识库", Icons.Default.Book),
    INSPIRATION("inspiration", "灵感", Icons.Default.Edit),
    PROFILE("profile", "我", Icons.Default.Person)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun KnowledgeApp() {
    var activeTab by remember { mutableStateOf(Tab.KNOWLEDGE) }
    var subPage by remember { mutableStateOf<String?>(null) }
    var selectedKbId by remember { mutableStateOf<String?>(null) }

    val noteViewModel: NoteEditorViewModel = viewModel(factory = ViewModelFactory)
    val homeViewModel: KnowledgeHomeViewModel = viewModel(factory = ViewModelFactory)
    val manageViewModel: KnowledgeManageViewModel = viewModel(factory = ViewModelFactory)
    val itemViewModel: KnowledgeItemListViewModel = viewModel(factory = ViewModelFactory)
    val askViewModel: AskViewModel = viewModel(factory = ViewModelFactory)

    Scaffold(
        bottomBar = {
            if (subPage == null) {
                BottomNavigationBar(
                    activeTab = activeTab,
                    onTabSelected = { 
                        activeTab = it 
                        subPage = null
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when {
                subPage == "context" -> KnowledgeContextScreen(onBack = { subPage = null })
                subPage == "fragments" -> FragmentOrganizeScreen(onBack = { subPage = null })
                subPage == "manage" -> KnowledgeManageScreen(
                    homeViewModel = homeViewModel,
                    manageViewModel = manageViewModel,
                    onBack = { subPage = null },
                    onOpenKbDetail = { kbId ->
                        selectedKbId = kbId
                        subPage = "detail"
                    }
                )
                subPage == "settings" -> SettingsScreen(
                    onBack = { subPage = null }
                )
                subPage == "detail" && selectedKbId != null -> {
                    itemViewModel.setKnowledgeBaseId(selectedKbId!!)
                    KnowledgeDetailScreen(
                        kbName = homeViewModel.knowledgeBases.value.find { it.id == selectedKbId }?.name ?: "知识管理",
                        viewModel = itemViewModel,
                        onBack = { subPage = "manage" },
                        onAskAI = { /* handle AI ask context */ }
                    )
                }
                else -> {
                    AnimatedContent(targetState = activeTab, label = "TabTransition") { tab ->
                        when (tab) {
                            Tab.KNOWLEDGE -> KnowledgeScreen(
                                viewModel = homeViewModel,
                                onOpenContext = { subPage = "context" },
                                onOpenFragments = { subPage = "fragments" },
                                onOpenKbDetail = { kbId ->
                                    selectedKbId = kbId
                                    subPage = "detail"
                                },
                                onOpenKbManage = { subPage = "manage" }
                            )
                            Tab.INSPIRATION -> InspirationScreen(viewModel = noteViewModel)
                            Tab.PROFILE -> ProfileScreen(onOpenSettings = { subPage = "settings" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BottomNavigationBar(
    activeTab: Tab,
    onTabSelected: (Tab) -> Unit
) {
    Surface(
        color = Color.White.copy(alpha = 0.95f),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(0.5.dp, Color(0xFFE5E5E5))
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.entries.forEach { tab ->
                val selected = activeTab == tab
                Column(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabSelected(tab) }
                        )
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) Color(0xFF147EC5) else Color(0xFF8BB9D8)
                    )
                    Text(
                        text = tab.label,
                        fontSize = 10.sp,
                        color = if (selected) Color(0xFF147EC5) else Color(0xFF8BB9D8)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun KnowledgeAppPreview() {
    KnowledgeApp()
}
