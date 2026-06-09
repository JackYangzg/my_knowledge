package com.my.knowledge.ui

import android.net.Uri
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.viewmodel.IntermediateDataViewModel
import com.my.knowledge.viewmodel.KnowledgeEditorViewModel
import com.my.knowledge.viewmodel.KnowledgeHomeViewModel
import com.my.knowledge.viewmodel.KnowledgeItemListViewModel
import com.my.knowledge.viewmodel.KnowledgeItemDetailViewModel
import com.my.knowledge.viewmodel.KnowledgeManageViewModel
import com.my.knowledge.viewmodel.ImportCenterViewModel
import com.my.knowledge.viewmodel.NoteEditorViewModel
import com.my.knowledge.viewmodel.ProcessingStatusViewModel
import com.my.knowledge.viewmodel.ProfileViewModel
import com.my.knowledge.viewmodel.RecycleBinViewModel
import com.my.knowledge.viewmodel.ThreadViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

enum class Tab(val id: String, val label: String, val icon: ImageVector) {
    KNOWLEDGE("knowledge", "知识库", Icons.Default.Book),
    INSPIRATION("inspiration", "灵感", Icons.Default.Edit),
    PROFILE("profile", "我", Icons.Default.Person)
}

sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Inspiration : Route("inspiration")
    data object Profile : Route("profile")
    data object Context : Route("context")
    data object Fragments : Route("fragments")
    data object Manage : Route("manage")
    data object Settings : Route("settings")
    data object LogCenter : Route("log_center")
    data object RecycleBin : Route("recycle_bin")
    data object IntermediateData : Route("intermediate/{kbId}") {
        fun create(kbId: String? = null) =
            if (kbId.isNullOrBlank()) "intermediate/_all" else "intermediate/${Uri.encode(kbId)}"
    }
    data object KnowledgeBaseDetail : Route("knowledge_base/{kbId}") {
        fun create(kbId: String) = "knowledge_base/${Uri.encode(kbId)}"
    }
    data object KnowledgeItemDetail : Route("knowledge_item/{kbId}/{itemId}") {
        fun create(kbId: String, itemId: String) = "knowledge_item/${Uri.encode(kbId)}/${Uri.encode(itemId)}"
    }
    data object NewNote : Route("new_note?kbName={kbName}") {
        fun create(kbName: String? = null): String =
            if (kbName.isNullOrBlank()) "new_note"
            else "new_note?kbName=${Uri.encode(kbName)}"
    }
    data object Ask : Route("ask/{scopeType}/{scopeId}/{title}") {
        fun create(scopeType: String, scopeId: String, title: String) =
            "ask/${Uri.encode(scopeType)}/${Uri.encode(scopeId)}/${Uri.encode(title)}"
    }
    data object ItemEditor : Route("item_editor/{itemId}") {
        fun create(itemId: String) = "item_editor/${Uri.encode(itemId)}"
    }
    /**
     * Markdown editor dedicated to knowledge items. Distinct from the
     * inspiration note editor (Route.ItemEditor) because the two
     * flows have different semantics:
     *   - inspiration:    free-form note → may or may not be saved as KB
     *   - knowledge item: already in the KB, edit-in-place updates the row
     * The header of the editor shows "编辑 [title]" so the user always
     * knows they're editing an existing knowledge entry, not a note.
     */
    data object KnowledgeEditor : Route("knowledge_editor/{itemId}") {
        fun create(itemId: String) = "knowledge_editor/${Uri.encode(itemId)}"
    }
    data object FragmentChainDetail : Route("fragment_chain/{chainId}") {
        fun create(chainId: String) = "fragment_chain/${Uri.encode(chainId)}"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun KnowledgeApp() {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Route.Home.path
    // Single KnowledgeRepository instance shared across the nav graph so
    // every Ask surface (the AskSheet sheets on Home/Detail/Viewer, plus
    // the legacy full-screen AskScreen route) reuses the same Room Flow
    // subscription graph instead of building per-screen DAOs.
    val context = androidx.compose.ui.platform.LocalContext.current
    val knowledgeRepository = remember(context) {
        DependencyProvider.provideKnowledgeRepository(context.applicationContext)
    }
    val activeTab = when (currentRoute) {
        Route.Inspiration.path -> Tab.INSPIRATION
        Route.Profile.path -> Tab.PROFILE
        else -> Tab.KNOWLEDGE
    }
    val topLevelRoutes = setOf(Route.Home.path, Route.Inspiration.path, Route.Profile.path)
    val noteViewModel: NoteEditorViewModel = viewModel(factory = ViewModelFactory)
    val homeViewModel: KnowledgeHomeViewModel = viewModel(factory = ViewModelFactory)
    val manageViewModel: KnowledgeManageViewModel = viewModel(factory = ViewModelFactory)
    val itemViewModel: KnowledgeItemListViewModel = viewModel(factory = ViewModelFactory)
    val askViewModel: AskViewModel = viewModel(factory = ViewModelFactory)
    val processingStatusViewModel: ProcessingStatusViewModel = viewModel(factory = ViewModelFactory)
    val importCenterViewModel: ImportCenterViewModel = viewModel(factory = ViewModelFactory)
    val profileViewModel: ProfileViewModel = viewModel(factory = ViewModelFactory)
    val threadViewModel: ThreadViewModel = viewModel(factory = ViewModelFactory)
    val detailViewModel: KnowledgeItemDetailViewModel = viewModel(factory = ViewModelFactory)
    val recycleBinViewModel: RecycleBinViewModel = viewModel(factory = ViewModelFactory)
    val intermediateDataViewModel: IntermediateDataViewModel = viewModel(factory = ViewModelFactory)
    val knowledgeEditorViewModel: KnowledgeEditorViewModel = viewModel(factory = ViewModelFactory)

    Scaffold(
        bottomBar = {
            if (currentRoute in topLevelRoutes) {
                BottomNavigationBar(
                    activeTab = activeTab,
                    onTabSelected = { tab ->
                        val route = when (tab) {
                            Tab.KNOWLEDGE -> Route.Home.path
                            Tab.INSPIRATION -> Route.Inspiration.path
                            Tab.PROFILE -> Route.Profile.path
                        }
                        navController.navigate(route) {
                            popUpTo(Route.Home.path) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Route.Home.path,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Route.Home.path) {
                    KnowledgeScreen(
                        viewModel = homeViewModel,
                        askViewModel = askViewModel,
                        knowledgeRepository = knowledgeRepository,
                        onOpenContext = { navController.navigate(Route.Context.path) },
                        onOpenFragments = { navController.navigate(Route.Fragments.path) },
                        onOpenKbDetail = { kbId -> navController.navigate(Route.KnowledgeBaseDetail.create(kbId)) },
                        onOpenKbManage = { navController.navigate(Route.Manage.path) }
                    )
                }
                composable(Route.Inspiration.path) {
                    InspirationScreen(
                        viewModel = noteViewModel,
                        onOpenItem = { itemId ->
                            navController.navigate(Route.KnowledgeItemDetail.create("inspiration", itemId))
                        }
                    )
                }
                composable(Route.Profile.path) {
                    ProfileScreen(
                        viewModel = profileViewModel,
                        onOpenSettings = { navController.navigate(Route.Settings.path) },
                        onOpenLogCenter = { navController.navigate(Route.LogCenter.path) },
                        onOpenRecycleBin = { navController.navigate(Route.RecycleBin.path) },
                        onOpenIntermediateData = { kbId ->
                            navController.navigate(Route.IntermediateData.create(kbId))
                        }
                    )
                }
                composable(Route.Context.path) {
                    KnowledgeContextScreen(
                        homeViewModel = homeViewModel,
                        threadViewModel = threadViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.Fragments.path) {
                    FragmentOrganizeScreen(
                        onBack = { navController.popBackStack() },
                        onChainClick = { chainId ->
                            navController.navigate(Route.FragmentChainDetail.create(chainId))
                        },
                    )
                }
                composable(Route.FragmentChainDetail.path) { backStack ->
                    val chainId = backStack.arguments?.getString("chainId").orEmpty()
                    FragmentChainDetailScreen(
                        chainId = chainId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Route.Manage.path) {
                    KnowledgeManageScreen(
                        homeViewModel = homeViewModel,
                        manageViewModel = manageViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenKbDetail = { kbId -> navController.navigate(Route.KnowledgeBaseDetail.create(kbId)) }
                    )
                }
                composable(Route.Settings.path) {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(Route.LogCenter.path) {
                    KnowledgeLogScreen(
                        importViewModel = importCenterViewModel,
                        processingViewModel = processingStatusViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.RecycleBin.path) {
                    RecycleBinScreen(
                        viewModel = recycleBinViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Route.IntermediateData.path,
                    arguments = listOf(
                        navArgument("kbId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { entry ->
                    val rawKbId = entry.arguments?.getString("kbId")
                    val kbId = rawKbId?.takeIf { it.isNotBlank() && it != "_all" }
                    LaunchedEffect(kbId) {
                        // Tell the view-model which knowledge base to scope to.
                        intermediateDataViewModel.setKbId(kbId)
                    }
                    IntermediateDataScreen(
                        viewModel = intermediateDataViewModel,
                        onBack = { navController.popBackStack() },
                        onViewDetail = { itemId ->
                            navController.navigate(Route.KnowledgeItemDetail.create("any", itemId))
                        },
                        onSwitchKb = { newKbId ->
                            navController.navigate(Route.IntermediateData.create(newKbId)) {
                                popUpTo(Route.IntermediateData.path) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }
                composable(
                    route = Route.KnowledgeBaseDetail.path,
                    arguments = listOf(navArgument("kbId") { type = NavType.StringType })
                ) { entry ->
                    val kbId = entry.arguments?.getString("kbId").orEmpty()
                    itemViewModel.setKnowledgeBaseId(kbId)
                    val kbBases by homeViewModel.knowledgeBases.collectAsState()
                    KnowledgeDetailScreen(
                        kbId = kbId,
                        kbName = kbBases.find { it.id == kbId }?.name ?: "知识管理",
                        viewModel = itemViewModel,
                        askViewModel = askViewModel,
                        knowledgeRepository = knowledgeRepository,
                        allKnowledgeBases = kbBases,
                        onBack = { navController.popBackStack() },
                        onOpenItem = { itemId -> navController.navigate(Route.KnowledgeItemDetail.create(kbId, itemId)) },
                        onOpenIntermediate = { baseId -> navController.navigate(Route.IntermediateData.create(baseId)) },
                        onOpenNewNote = { kbName -> navController.navigate(Route.NewNote.create(kbName)) }
                    )
                }
                composable(
                    route = Route.KnowledgeItemDetail.path,
                    arguments = listOf(
                        navArgument("kbId") { type = NavType.StringType },
                        navArgument("itemId") { type = NavType.StringType }
                    )
                ) { entry ->
                    val itemId = entry.arguments?.getString("itemId").orEmpty()
                    KnowledgeViewerScreen(
                        itemId = itemId,
                        viewModel = detailViewModel,
                        askViewModel = askViewModel,
                        knowledgeRepository = knowledgeRepository,
                        onBack = { navController.popBackStack() },
                        onOpenItem = { nextItemId ->
                            val kbId = entry.arguments?.getString("kbId").orEmpty()
                            navController.navigate(Route.KnowledgeItemDetail.create(kbId, nextItemId))
                        },
                        onEditItem = { editId ->
                            navController.navigate(Route.KnowledgeEditor.create(editId))
                        }
                    )
                }
                composable(
                    route = Route.ItemEditor.path,
                    arguments = listOf(navArgument("itemId") { type = NavType.StringType })
                ) { entry ->
                    val itemId = entry.arguments?.getString("itemId").orEmpty()
                    LaunchedEffect(itemId) {
                        // Load the existing knowledge item into the editor so
                        // the user sees its current title/content and any save
                        // re-uses the same row (rawNoteId dedup).
                        noteViewModel.loadFromKnowledgeItem(itemId)
                    }
                    InspirationScreen(
                        viewModel = noteViewModel,
                        startInEditor = true,
                        onOpenItem = { nextItemId ->
                            navController.navigate(Route.KnowledgeItemDetail.create("inspiration", nextItemId))
                        }
                    )
                }
                composable(
                    route = Route.KnowledgeEditor.path,
                    arguments = listOf(navArgument("itemId") { type = NavType.StringType })
                ) { entry ->
                    val itemId = entry.arguments?.getString("itemId").orEmpty()
                    KnowledgeEditorScreen(
                        itemId = itemId,
                        viewModel = knowledgeEditorViewModel,
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Route.Ask.path,
                    arguments = listOf(
                        navArgument("scopeType") { type = NavType.StringType },
                        navArgument("scopeId") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType }
                    )
                ) { entry ->
                    val scopeType = entry.arguments?.getString("scopeType").orEmpty()
                    val scopeId = entry.arguments?.getString("scopeId").orEmpty()
                    val title = entry.arguments?.getString("title").orEmpty()
                    LaunchedEffect(scopeType, scopeId) {
                        if (scopeType.isNotBlank() && scopeId.isNotBlank()) {
                            askViewModel.setScope(scopeType, scopeId)
                        }
                    }
                    AskScreen(
                        viewModel = askViewModel,
                        itemTitle = title,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Route.NewNote.path,
                    arguments = listOf(navArgument("kbName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    })
                ) { entry ->
                    val rawKbName = entry.arguments?.getString("kbName")
                    val initialKbName = rawKbName?.takeIf { it.isNotBlank() } ?: "灵感空间"
                    val kbBases by homeViewModel.knowledgeBases.collectAsState()
                    val resolvedKbName = kbBases.find { it.name == initialKbName }?.name ?: initialKbName
                    // T1: keyed on initialKbName — re-entering with a different
                    // KB name resets stale content; same KB keeps the in-flight
                    // edit (no-op since createNewNote() sees the same flag).
                    LaunchedEffect(initialKbName) {
                        noteViewModel.createNewNote()
                    }
                    ReusableNoteEditor(
                        viewModel = noteViewModel,
                        initialKbName = resolvedKbName,
                        onDismiss = { navController.popBackStack() }
                    )
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

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(
        color = Color.White.copy(alpha = 0.95f),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(0.5.dp, palette.borderDefault)
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
                        tint = if (selected) palette.brand else Color(0xFF8BB9D8)
                    )
                    Text(
                        text = tab.label, style = MaterialTheme.typography.labelSmall,
                        color = if (selected) palette.brand else Color(0xFF8BB9D8)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun KnowledgeAppPreview() {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    KnowledgeApp()
}
