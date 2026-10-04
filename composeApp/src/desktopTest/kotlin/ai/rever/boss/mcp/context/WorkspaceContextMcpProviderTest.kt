package ai.rever.boss.mcp.context

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.topofmind.TopOfMindStateHolder
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectStateRegistry
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceContextMcpProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class StubTabInfo(
        override val id: String,
        override val typeId: TabTypeId,
        override val title: String,
        val filePath: String? = null,
        val currentUrl: String? = null,
        val initialUrl: String? = null,
    ) : TabInfo {
        override val icon: ImageVector get() = error("Not needed for tests")
        override val tabIcon: TabIcon? get() = null
    }

    private class StubComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    private fun createActiveTab(
        id: String,
        typeIdString: String,
        title: String,
        filePath: String? = null,
        currentUrl: String? = null,
        initialUrl: String? = null,
        workspaceId: String = "ws-1",
        workspaceName: String = "Main",
        panelId: String = "panel-1",
        windowId: String = "window-1",
        isSelected: Boolean = false,
        isPanelActive: Boolean = false,
    ): ActiveTab {
        val tabInfo =
            StubTabInfo(
                id = id,
                typeId = TabTypeId(typeIdString, "test.plugin"),
                title = title,
                filePath = filePath,
                currentUrl = currentUrl,
                initialUrl = initialUrl,
            )
        return ActiveTab(
            tabInfo = tabInfo,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            panelId = panelId,
            windowId = windowId,
            isSelected = isSelected,
            isPanelActive = isPanelActive,
        )
    }

    private companion object {
        const val TEST_WINDOW_1 = "test-window-1"
        const val TEST_WINDOW_2 = "test-window-2"
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(CodeEditorTabType) { config, ctx -> StubComponent(ctx, config, CodeEditorTabType) }
            registerTabType(FluckTabType) { config, ctx -> StubComponent(ctx, config, FluckTabType) }
        }

    @BeforeTest
    fun setUp() {
        SplitViewStateRegistry.getAllStates().keys.forEach {
            SplitViewStateRegistry.unregister(it)
        }
        WindowProjectStateRegistry.getAllWindowIds().forEach {
            WindowProjectStateRegistry.unregister(it)
        }
        TopOfMindStateHolder.updateActiveTabs(emptyList())
    }

    @AfterTest
    fun tearDown() {
        SplitViewStateRegistry.getAllStates().keys.forEach {
            SplitViewStateRegistry.unregister(it)
        }
        WindowProjectStateRegistry.getAllWindowIds().forEach {
            WindowProjectStateRegistry.unregister(it)
        }
        TopOfMindStateHolder.updateActiveTabs(emptyList())
    }

    @Test
    fun `empty tabs and null project path returns valid empty snapshot`(): Unit =
        runBlocking {
            val provider =
                WorkspaceContextMcpProvider(
                    snapshotSupplier = {
                        WorkspaceSnapshotCollector.collect(
                            activeTabsSupplier = { emptyList() },
                            globalProjectPathSupplier = { null },
                        )
                    },
                )

            val tool = provider.tools().first { it.name == "get_workspace_context" }
            assertFalse(tool.requiresAdmin)
            assertEquals(listOf("workspace.context"), tool.requiredPermissions)
            assertTrue(tool.readOnly)

            val result = tool.handler.call(McpToolArgs(emptyMap(), "{}"))
            assertFalse(result.isError)

            val snapshot = json.decodeFromString(WorkspaceSnapshot.serializer(), result.text)
            assertNull(snapshot.activeProjectPath)
            assertTrue(snapshot.openTabs.isEmpty())
            assertEquals(0, snapshot.tabCounts.total)
            assertEquals(0, snapshot.tabCounts.editor)
            assertEquals(0, snapshot.tabCounts.terminal)
            assertEquals(0, snapshot.tabCounts.browser)
            assertEquals(0, snapshot.tabCounts.other)
            assertNull(snapshot.activeEditorFile)
        }

    @Test
    fun `categorizes tabs into editor, terminal, browser and other`(): Unit =
        runBlocking {
            val tabs =
                listOf(
                    createActiveTab(
                        "t1",
                        "editor",
                        "Main.kt",
                        filePath = "/path/to/project/src/Main.kt",
                        isSelected = true,
                        isPanelActive = true,
                    ),
                    createActiveTab("t2", "terminal", "Terminal 1"),
                    createActiveTab("t3", "fluck", "Google", currentUrl = "https://google.com"),
                    createActiveTab("t4", "custom_plugin_tab", "Custom View"),
                )

            val provider =
                WorkspaceContextMcpProvider(
                    snapshotSupplier = {
                        WorkspaceSnapshotCollector.collect(
                            activeTabsSupplier = { tabs },
                            globalProjectPathSupplier = { "/path/to/project" },
                        )
                    },
                )

            val tool = provider.tools().first { it.name == "get_workspace_context" }
            val result = tool.handler.call(McpToolArgs(emptyMap(), "{}"))
            val snapshot = json.decodeFromString(WorkspaceSnapshot.serializer(), result.text)

            assertEquals(4, snapshot.tabCounts.total)
            assertEquals(1, snapshot.tabCounts.editor)
            assertEquals(1, snapshot.tabCounts.terminal)
            assertEquals(1, snapshot.tabCounts.browser)
            assertEquals(1, snapshot.tabCounts.other)

            val editorTab = snapshot.openTabs.first { it.type == "editor" }
            assertEquals("Main.kt", editorTab.title)
            assertEquals("/path/to/project/src/Main.kt", editorTab.filePath)
            assertEquals("src/Main.kt", editorTab.relativePath)
            assertTrue(editorTab.isSelected)

            val browserTab = snapshot.openTabs.first { it.type == "browser" }
            assertEquals("https://google.com", browserTab.browserUrl)

            val activeEditorFile = assertNotNull(snapshot.activeEditorFile)
            assertEquals("/path/to/project/src/Main.kt", activeEditorFile.absolutePath)
            assertEquals("src/Main.kt", activeEditorFile.relativePath)
            assertEquals("Main.kt", activeEditorFile.fileName)
        }

    @Test
    fun `browser tab reflection falls back to initialUrl when currentUrl is null`(): Unit =
        runBlocking {
            val tabs =
                listOf(
                    createActiveTab(
                        "t1",
                        "fluck",
                        "Loading Page",
                        currentUrl = null,
                        initialUrl = "https://example.com/initial",
                    ),
                )

            val snapshot =
                WorkspaceSnapshotCollector.collect(
                    activeTabsSupplier = { tabs },
                    globalProjectPathSupplier = { null },
                )

            val browserTab = snapshot.openTabs.first { it.type == "browser" }
            assertEquals("https://example.com/initial", browserTab.browserUrl)
        }

    @Test
    fun `computes safe relative paths without cross-drive crash on Windows and handles edge cases`() {
        val winProj = "C:\\Users\\dev\\project"
        val winFileSameDrive = "C:\\Users\\dev\\project\\src\\App.kt"
        val winFileOtherDrive = "D:\\data\\Other.kt"

        val relSame = WorkspaceSnapshotCollector.computeSafeRelativePath(winFileSameDrive, winProj)
        assertEquals("src/App.kt", relSame)

        val relOther = WorkspaceSnapshotCollector.computeSafeRelativePath(winFileOtherDrive, winProj)
        assertNull(relOther)

        // Same drive with case difference on drive letter (Windows)
        val winProjLower = "c:\\users\\dev\\project"
        val relLower = WorkspaceSnapshotCollector.computeSafeRelativePath(winFileSameDrive, winProjLower)
        assertEquals("src/App.kt", relLower)

        // Trailing slash handling
        val winProjTrailing = "C:\\Users\\dev\\project\\"
        val relTrailing = WorkspaceSnapshotCollector.computeSafeRelativePath(winFileSameDrive, winProjTrailing)
        assertEquals("src/App.kt", relTrailing)

        // UNC path support
        val uncProj = "\\\\server\\share\\repo"
        val uncFile = "\\\\server\\share\\repo\\file.kt"
        val uncOther = "\\\\server\\othershare\\file.kt"
        assertEquals("file.kt", WorkspaceSnapshotCollector.computeSafeRelativePath(uncFile, uncProj))
        assertNull(WorkspaceSnapshotCollector.computeSafeRelativePath(uncOther, uncProj))

        // Unix path matching is case-sensitive
        val unixProj = "/home/user/project"
        val unixFile = "/home/user/project/README.md"
        val relUnix = WorkspaceSnapshotCollector.computeSafeRelativePath(unixFile, unixProj)
        assertEquals("README.md", relUnix)

        // Unix case difference must NOT match
        val unixFileCaseDiff = "/home/user/Project/README.md"
        assertNull(WorkspaceSnapshotCollector.computeSafeRelativePath(unixFileCaseDiff, unixProj))

        // Sibling directory prefix must not match
        val unixSibling = "/home/user/project2/README.md"
        assertNull(WorkspaceSnapshotCollector.computeSafeRelativePath(unixSibling, unixProj))
    }

    @Test
    fun `get_active_editor_file tool returns consistent JSON shape on hit and miss`(): Unit =
        runBlocking {
            val providerWithFile =
                WorkspaceContextMcpProvider(
                    activeEditorSupplier = {
                        ActiveEditorFileSnapshot(
                            absolutePath = "C:/repo/File.kt",
                            relativePath = "File.kt",
                            fileName = "File.kt",
                        )
                    },
                )

            val fileTool = providerWithFile.tools().first { it.name == "get_active_editor_file" }
            assertFalse(fileTool.requiresAdmin)
            assertEquals(listOf("workspace.context"), fileTool.requiredPermissions)

            val resultWithFile = fileTool.handler.call(McpToolArgs(emptyMap(), "{}"))
            assertFalse(resultWithFile.isError)
            val decodedHit = json.decodeFromString(ActiveEditorFileResult.serializer(), resultWithFile.text)
            assertTrue(decodedHit.available)
            val activeEditor = assertNotNull(decodedHit.activeEditorFile)
            assertEquals("File.kt", activeEditor.fileName)
            assertEquals("C:/repo/File.kt", activeEditor.absolutePath)

            val providerWithoutFile =
                WorkspaceContextMcpProvider(
                    activeEditorSupplier = { null },
                )
            val resultWithoutFile =
                providerWithoutFile
                    .tools()
                    .first { it.name == "get_active_editor_file" }
                    .handler
                    .call(McpToolArgs(emptyMap(), "{}"))
            assertFalse(resultWithoutFile.isError)
            val decodedMiss = json.decodeFromString(ActiveEditorFileResult.serializer(), resultWithoutFile.text)
            assertFalse(decodedMiss.available)
            assertNull(decodedMiss.activeEditorFile)
        }

    @Test
    fun `only focused editor tab is returned as activeEditorFile - background editor tabs are ignored`(): Unit =
        runBlocking {
            // First editor tab is not selected (background), second editor tab IS selected and in active panel
            val tabs =
                listOf(
                    createActiveTab(
                        "t1",
                        "editor",
                        "Background.kt",
                        filePath = "/repo/Background.kt",
                        isSelected = false,
                        isPanelActive = true,
                    ),
                    createActiveTab(
                        "t2",
                        "editor",
                        "Focused.kt",
                        filePath = "/repo/Focused.kt",
                        isSelected = true,
                        isPanelActive = true,
                    ),
                )

            val activeFile =
                WorkspaceSnapshotCollector.findActiveEditorFile(
                    activeTabsSupplier = { tabs },
                    projectPathResolver = { "/repo" },
                )

            assertNotNull(activeFile)
            assertEquals("Focused.kt", activeFile.fileName)
            assertEquals("/repo/Focused.kt", activeFile.absolutePath)

            // If active tab is terminal, no active editor file is returned even if an editor tab is open
            val terminalActiveTabs =
                listOf(
                    createActiveTab(
                        "t1",
                        "editor",
                        "Background.kt",
                        filePath = "/repo/Background.kt",
                        isSelected = false,
                        isPanelActive = true,
                    ),
                    createActiveTab(
                        "t2",
                        "terminal",
                        "Terminal",
                        isSelected = true,
                        isPanelActive = true,
                    ),
                )

            val noActiveFile =
                WorkspaceSnapshotCollector.findActiveEditorFile(
                    activeTabsSupplier = { terminalActiveTabs },
                    projectPathResolver = { "/repo" },
                )
            assertNull(noActiveFile)
        }

    @Test
    fun `both tools require workspace_context permission and are classified as sensitive reads in policy catalog`() {
        val provider = WorkspaceContextMcpProvider()
        val tools = provider.tools()

        assertEquals(2, tools.size)
        for (tool in tools) {
            assertEquals(
                listOf("workspace.context"),
                tool.requiredPermissions,
                "Tool ${tool.name} must require workspace.context permission",
            )
            assertTrue(
                McpMutatingToolCatalog.isMutating(tool.name, declaredReadOnly = tool.readOnly),
                "Tool ${tool.name} must be classified as sensitive read requiring approval",
            )
        }
    }

    @Test
    fun `live tabs read from SplitViewStateRegistry when cache is empty`(): Unit =
        runBlocking {
            val windowState = SplitViewState(tabRegistry, TEST_WINDOW_1)
            windowState.preserveCurrentState("w1", "Workspace")
            SplitViewStateRegistry.register(TEST_WINDOW_1, windowState)

            val panel = windowState.getPanel("main")!!.tabsComponent
            val editorInfo = EditorTabInfo(id = "e1", title = "RealEditor.kt", filePath = "/path/to/RealEditor.kt")
            val fluckInfo =
                FluckTabInfo(
                    id = "b1",
                    typeId = TabTypeId("fluck"),
                    _title = "RealBrowser",
                    url = "https://example.com/live",
                    _currentUrl = "https://example.com/live",
                )

            assertEquals(0, panel.addTab(editorInfo))
            assertEquals(1, panel.addTab(fluckInfo))
            panel.selectTab(0)

            // TopOfMindStateHolder is empty
            assertTrue(TopOfMindStateHolder.activeTabs.value.isEmpty())

            // Default collect reads live SplitViewStateRegistry
            val snapshot =
                WorkspaceSnapshotCollector.collect(
                    projectPathResolver = { "/path/to" },
                    activeWindowIdSupplier = { TEST_WINDOW_1 },
                )

            assertEquals(2, snapshot.openTabs.size)
            val editorTab = snapshot.openTabs.first { it.type == "editor" }
            assertEquals("/path/to/RealEditor.kt", editorTab.filePath)
            assertEquals("RealEditor.kt", editorTab.relativePath)
            assertTrue(editorTab.isSelected)

            val browserTab = snapshot.openTabs.first { it.type == "browser" }
            assertEquals("https://example.com/live", browserTab.browserUrl)

            val activeFile = assertNotNull(snapshot.activeEditorFile)
            assertEquals("RealEditor.kt", activeFile.fileName)
            assertEquals("/path/to/RealEditor.kt", activeFile.absolutePath)
        }

    @Test
    fun `focused editor follows live panel activeTab selection in real SplitViewState`(): Unit =
        runBlocking {
            val windowState = SplitViewState(tabRegistry, TEST_WINDOW_1)
            windowState.preserveCurrentState("w1", "Workspace")
            SplitViewStateRegistry.register(TEST_WINDOW_1, windowState)

            val panel = windowState.getPanel("main")!!.tabsComponent
            val editor1 = EditorTabInfo(id = "e1", title = "First.kt", filePath = "/repo/First.kt")
            val editor2 = EditorTabInfo(id = "e2", title = "Second.kt", filePath = "/repo/Second.kt")
            val browser =
                FluckTabInfo(
                    id = "b1",
                    typeId = TabTypeId("fluck"),
                    _title = "Browser",
                    url = "https://example.com",
                    _currentUrl = "https://example.com",
                )

            panel.addTab(editor1)
            panel.addTab(editor2)
            panel.addTab(browser)

            // Select Second.kt (index 1)
            panel.selectTab(1)

            val activeFileSecond =
                WorkspaceSnapshotCollector.findActiveEditorFile(
                    projectPathResolver = { "/repo" },
                    activeWindowIdSupplier = { TEST_WINDOW_1 },
                )
            assertNotNull(activeFileSecond)
            assertEquals("Second.kt", activeFileSecond.fileName)

            // Select Browser (index 2) - should return null
            panel.selectTab(2)
            val activeFileBrowser =
                WorkspaceSnapshotCollector.findActiveEditorFile(
                    projectPathResolver = { "/repo" },
                    activeWindowIdSupplier = { TEST_WINDOW_1 },
                )
            assertNull(activeFileBrowser)
        }

    @Test
    fun `resolves project path per window across multiple registered windows`(): Unit =
        runBlocking {
            val proj1 = Project(name = "RepoOne", path = "/repos/project1", lastOpened = 1000L)
            val proj2 = Project(name = "RepoTwo", path = "/repos/project2", lastOpened = 2000L)

            WindowProjectStateRegistry.getOrCreate(TEST_WINDOW_1).selectProject(proj1)
            WindowProjectStateRegistry.getOrCreate(TEST_WINDOW_2).selectProject(proj2)

            val tabs =
                listOf(
                    createActiveTab(
                        id = "t1",
                        typeIdString = "editor",
                        title = "One.kt",
                        filePath = "/repos/project1/src/One.kt",
                        windowId = TEST_WINDOW_1,
                    ),
                    createActiveTab(
                        id = "t2",
                        typeIdString = "editor",
                        title = "Two.kt",
                        filePath = "/repos/project2/src/Two.kt",
                        windowId = TEST_WINDOW_2,
                    ),
                )

            val snapshot =
                WorkspaceSnapshotCollector.collect(
                    activeTabsSupplier = { tabs },
                    activeWindowIdSupplier = { TEST_WINDOW_1 },
                )

            assertEquals("/repos/project1", snapshot.activeProjectPath)

            val tab1 = snapshot.openTabs.first { it.tabId == "t1" }
            val tab2 = snapshot.openTabs.first { it.tabId == "t2" }

            assertEquals("src/One.kt", tab1.relativePath)
            assertEquals("src/Two.kt", tab2.relativePath)
        }
}
