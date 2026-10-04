package ai.rever.boss.mcp.context

import ai.rever.boss.components.dialogs.TabCollector
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.git.GitService
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowProjectStateRegistry
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

@Suppress("TooManyFunctions", "LargeClass")
object WorkspaceSnapshotCollector {
    private val logger = BossLogger.forComponent("WorkspaceSnapshotCollector")
    private val methodCache = ConcurrentHashMap<Pair<Class<*>, String>, Optional<Method>>()

    @Suppress("LongMethod")
    fun collect(
        activeTabsSupplier: () -> List<ActiveTab> = { TabCollector.collectAllTabs() },
        globalProjectPathSupplier: () -> String? = { GitService.getCurrentProjectPath() },
        projectPathResolver: (windowId: String) -> String? = { windowId ->
            WindowProjectStateRegistry
                .get(windowId)
                ?.selectedProject
                ?.value
                ?.path
                ?.ifBlank { null }
                ?: globalProjectPathSupplier()
        },
        activeWindowIdSupplier: () -> String? = { WindowFocusManager.resolveActionableWindowId() },
    ): WorkspaceSnapshot {
        val activeWindowId = activeWindowIdSupplier()
        val activeProjectPath = activeWindowId?.let { projectPathResolver(it) } ?: globalProjectPathSupplier()
        val rawTabs = activeTabsSupplier()

        var editorCount = 0
        var terminalCount = 0
        var browserCount = 0
        var otherCount = 0

        val openTabs =
            rawTabs.map { tab ->
                val category = categorizeTab(tab.tabInfo.typeId.typeId)
                when (category) {
                    "editor" -> editorCount++
                    "terminal" -> terminalCount++
                    "browser" -> browserCount++
                    else -> otherCount++
                }

                val filePath = extractFilePath(tab.tabInfo)
                val windowProjPath = projectPathResolver(tab.windowId) ?: globalProjectPathSupplier()
                val relPath =
                    if (filePath != null && windowProjPath != null) {
                        computeSafeRelativePath(filePath, windowProjPath)
                    } else {
                        null
                    }

                TabSnapshot(
                    tabId = tab.tabInfo.id,
                    windowId = tab.windowId,
                    panelId = tab.panelId,
                    workspaceId = tab.workspaceId,
                    workspaceName = tab.workspaceName,
                    title = tab.tabInfo.title,
                    type = category,
                    filePath = filePath,
                    relativePath = relPath,
                    browserUrl = extractBrowserUrl(tab.tabInfo),
                    isSelected = tab.isSelected,
                )
            }

        val activeEditor =
            findActiveEditorFile(
                activeTabsSupplier = { rawTabs },
                globalProjectPathSupplier = globalProjectPathSupplier,
                projectPathResolver = projectPathResolver,
                activeWindowIdSupplier = { activeWindowId },
            )

        return WorkspaceSnapshot(
            activeProjectPath = activeProjectPath,
            openTabs = openTabs,
            tabCounts =
                TabCounts(
                    total = openTabs.size,
                    editor = editorCount,
                    terminal = terminalCount,
                    browser = browserCount,
                    other = otherCount,
                ),
            activeEditorFile = activeEditor,
        )
    }

    /**
     * Resolves the currently focused/active editor file in the actionable window.
     *
     * Directly queries live [SplitViewStateRegistry] when available (checking the active tab
     * of the active panel in the focused window first), with fallback to inspecting the
     * collected [ActiveTab] list for tabs marked [ActiveTab.isPanelActive] and [ActiveTab.isSelected].
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod")
    fun findActiveEditorFile(
        activeTabsSupplier: () -> List<ActiveTab> = { TabCollector.collectAllTabs() },
        globalProjectPathSupplier: () -> String? = { GitService.getCurrentProjectPath() },
        projectPathResolver: (windowId: String) -> String? = { windowId ->
            WindowProjectStateRegistry
                .get(windowId)
                ?.selectedProject
                ?.value
                ?.path
                ?.ifBlank { null }
                ?: globalProjectPathSupplier()
        },
        activeWindowIdSupplier: () -> String? = { WindowFocusManager.resolveActionableWindowId() },
    ): ActiveEditorFileSnapshot? {
        val activeWindowId = activeWindowIdSupplier()

        // 1. Direct inspection from SplitViewStateRegistry when live state is available
        val registeredStates = SplitViewStateRegistry.getAllStates()
        if (registeredStates.isNotEmpty()) {
            val candidateWindows = mutableListOf<Pair<String, ai.rever.boss.components.window_panel.SplitViewState>>()
            if (activeWindowId != null && registeredStates.containsKey(activeWindowId)) {
                candidateWindows.add(activeWindowId to registeredStates.getValue(activeWindowId))
            }
            registeredStates.forEach { (wId, state) ->
                if (wId != activeWindowId) {
                    candidateWindows.add(wId to state)
                }
            }

            for ((windowId, splitState) in candidateWindows) {
                findEditorInSplitState(splitState, windowId, projectPathResolver, globalProjectPathSupplier)?.let {
                    return it
                }
            }
            return null
        }

        // 2. Fallback when using injected activeTabsSupplier (e.g. unit tests without registry)
        val rawTabs = activeTabsSupplier()
        val focusedTab =
            rawTabs.firstOrNull {
                it.windowId == activeWindowId && it.isPanelActive && it.isSelected &&
                    categorizeTab(it.tabInfo.typeId.typeId) == "editor"
            } ?: rawTabs.firstOrNull {
                it.isPanelActive && it.isSelected && categorizeTab(it.tabInfo.typeId.typeId) == "editor"
            } ?: rawTabs.firstOrNull {
                it.windowId == activeWindowId && it.isSelected && categorizeTab(it.tabInfo.typeId.typeId) == "editor"
            } ?: rawTabs.firstOrNull {
                it.isSelected && categorizeTab(it.tabInfo.typeId.typeId) == "editor"
            }

        return focusedTab?.let { tab ->
            extractFilePath(tab.tabInfo)?.let { path ->
                val projPath = projectPathResolver(tab.windowId) ?: globalProjectPathSupplier()
                toActiveEditorFileSnapshot(path, projPath)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun findEditorInSplitState(
        splitState: ai.rever.boss.components.window_panel.SplitViewState,
        windowId: String,
        projectPathResolver: (windowId: String) -> String?,
        globalProjectPathSupplier: () -> String?,
    ): ActiveEditorFileSnapshot? {
        // Priority A: The active panel's active tab
        val activePanel =
            splitState.getPanel(splitState.activePanelId) ?: splitState.getAllPanels().firstOrNull()
        val activeTab =
            activePanel
                ?.tabsComponent
                ?.tabsState
                ?.value
                ?.activeTab
        if (activeTab != null && categorizeTab(activeTab.typeId.typeId) == "editor") {
            val filePath = extractFilePath(activeTab)
            if (filePath != null) {
                val projPath = projectPathResolver(windowId) ?: globalProjectPathSupplier()
                return toActiveEditorFileSnapshot(filePath, projPath)
            }
        }

        // Priority B: An active tab in any other panel of this window's active workspace
        val panelEditorTab =
            splitState
                .getAllPanels()
                .asSequence()
                .mapNotNull { it.tabsComponent.tabsState.value.activeTab }
                .firstOrNull { categorizeTab(it.typeId.typeId) == "editor" }
        if (panelEditorTab != null) {
            val filePath = extractFilePath(panelEditorTab)
            if (filePath != null) {
                val projPath = projectPathResolver(windowId) ?: globalProjectPathSupplier()
                return toActiveEditorFileSnapshot(filePath, projPath)
            }
        }
        return null
    }

    private fun toActiveEditorFileSnapshot(
        path: String,
        projectPath: String?,
    ): ActiveEditorFileSnapshot {
        val fileName = path.replace('\\', '/').substringAfterLast('/')
        val rel = if (projectPath != null) computeSafeRelativePath(path, projectPath) else null
        return ActiveEditorFileSnapshot(
            absolutePath = path,
            relativePath = rel,
            fileName = fileName,
        )
    }

    private fun categorizeTab(rawTypeId: String): String {
        val typeId = rawTypeId.lowercase()
        return when {
            typeId == "editor" || typeId.contains("codeeditor") -> "editor"
            typeId == "terminal" || typeId.contains("term") -> "terminal"
            typeId == "fluck" || typeId.contains("browser") -> "browser"
            else -> "other"
        }
    }

    @Suppress("ReturnCount")
    private fun extractFilePath(tabInfo: TabInfo): String? {
        if (categorizeTab(tabInfo.typeId.typeId) != "editor") return null

        if (tabInfo is EditorTabInfo) {
            return tabInfo.filePath?.ifBlank { null }
        }

        return invokeGetter(tabInfo, "getFilePath")
    }

    @Suppress("ReturnCount")
    private fun extractBrowserUrl(tabInfo: TabInfo): String? {
        if (categorizeTab(tabInfo.typeId.typeId) != "browser") return null

        if (tabInfo is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo) {
            return (tabInfo.currentUrl.ifBlank { null } ?: tabInfo.url.ifBlank { null })
        }

        // Reflection fallback: getCurrentUrl, then getInitialUrl, then getUrl
        val currentUrl = invokeGetter(tabInfo, "getCurrentUrl")
        if (currentUrl != null) return currentUrl

        val initialUrl = invokeGetter(tabInfo, "getInitialUrl")
        if (initialUrl != null) return initialUrl

        return invokeGetter(tabInfo, "getUrl")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun invokeGetter(
        target: Any,
        methodName: String,
    ): String? {
        val method = findCachedMethod(target.javaClass, methodName) ?: return null
        return try {
            (method.invoke(target) as? String)?.ifBlank { null }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "Failed to invoke $methodName on tab",
                mapOf("class" to target.javaClass.name, "error" to (e.message ?: "")),
            )
            null
        }
    }

    private fun findCachedMethod(
        clazz: Class<*>,
        methodName: String,
    ): Method? =
        methodCache
            .computeIfAbsent(clazz to methodName) {
                Optional.ofNullable(runCatching { clazz.getMethod(methodName) }.getOrNull())
            }.orElse(null)

    @Suppress("ComplexCondition")
    internal fun computeSafeRelativePath(
        filePath: String,
        projectPath: String,
    ): String? {
        val normFile = filePath.replace('\\', '/').trimEnd('/')
        val normProj = projectPath.replace('\\', '/').trimEnd('/')

        val fileDrive = extractDrive(normFile)
        val projDrive = extractDrive(normProj)
        if (fileDrive != null && projDrive != null && !fileDrive.equals(projDrive, ignoreCase = true)) {
            return null
        }

        val isWindows =
            fileDrive != null || projDrive != null ||
                filePath.startsWith("\\\\") || projectPath.startsWith("\\\\") ||
                filePath.contains('\\') || projectPath.contains('\\')

        val fileForMatch = normalizeForMatching(normFile, fileDrive, isWindows)
        val projForMatch = normalizeForMatching(normProj, projDrive, isWindows)

        val prefix = if (projForMatch == "/") "/" else "$projForMatch/"
        return when {
            fileForMatch.startsWith(prefix, ignoreCase = isWindows) -> normFile.substring(prefix.length)
            fileForMatch.equals(projForMatch, ignoreCase = isWindows) -> ""
            else -> null
        }
    }

    @Suppress("ComplexCondition")
    private fun extractDrive(path: String): String? {
        if (path.length >= 2 && path[1] == ':' && ((path[0] in 'A'..'Z') || (path[0] in 'a'..'z'))) {
            return path.substring(0, 2).lowercase()
        }
        return null
    }

    @Suppress("ComplexCondition")
    private fun normalizeForMatching(
        path: String,
        drive: String?,
        isWindows: Boolean,
    ): String {
        if (isWindows && drive != null && path.length >= 2 && path[1] == ':') {
            return drive + path.substring(2)
        }
        return path
    }
}
