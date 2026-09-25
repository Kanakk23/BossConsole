package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Round-trip tests for [WorkspacePortability]: a materialised Space is exported to a
 * project-relative form and resolved back against a project path faithfully, including the
 * shell-quoted project path inside a terminal command.
 */
class WorkspacePortabilityTest {
    private val projectPath = "/Users/me/proj"

    private fun sampleWorkspace(pp: String?): LayoutWorkspace {
        val command = "cd ${CommandProcessor.quotePath("/Users/me/proj")} && claude"
        val panel =
            PanelConfig(
                id = "main",
                tabs =
                    listOf(
                        TabConfig(type = "browser", title = "B", url = "/Users/me/proj/index.html"),
                        TabConfig(type = "editor", title = "E", filePath = "/Users/me/proj/src/A.kt"),
                        TabConfig(
                            type = "terminal",
                            title = "T",
                            initialCommand = command,
                            workingDirectory = "/Users/me/proj",
                        ),
                    ),
            )
        return LayoutWorkspace(
            id = "workspace-1",
            name = "Sample",
            description = "d",
            layout = SplitConfig.SinglePanel(panel),
            projectPath = pp,
        )
    }

    private fun tabs(ws: LayoutWorkspace) = (ws.layout as SplitConfig.SinglePanel).panel.tabs

    @Test
    fun `toPortable replaces the project path with the placeholder and clears projectPath`() {
        val portable = WorkspacePortability.toPortable(sampleWorkspace(projectPath))
        assertNull(portable.projectPath)
        val portableTabs = tabs(portable)
        assertEquals("${WorkspacePortability.PLACEHOLDER}/index.html", portableTabs[0].url)
        assertEquals("${WorkspacePortability.PLACEHOLDER}/src/A.kt", portableTabs[1].filePath)
        assertEquals(WorkspacePortability.PLACEHOLDER, portableTabs[2].workingDirectory)
        assertEquals("cd ${WorkspacePortability.PLACEHOLDER} && claude", portableTabs[2].initialCommand)
        // The absolute path must not survive anywhere in the serialized portable form.
        assertFalse(WorkspacePortability.toPortableJson(sampleWorkspace(projectPath)).contains(projectPath))
    }

    @Test
    fun `a round trip to the same project path reproduces the original tabs`() {
        val original = sampleWorkspace(projectPath)
        val portable = WorkspacePortability.toPortable(original)
        val restored = WorkspacePortability.fromPortable(portable, projectPath)

        assertEquals(tabs(original), tabs(restored))
        assertEquals(projectPath, restored.projectPath)
    }

    @Test
    fun `fromPortable binds a new project path and mints a fresh id`() {
        val portable = WorkspacePortability.toPortable(sampleWorkspace(projectPath))
        val restored = WorkspacePortability.fromPortable(portable, "/opt/other")

        val restoredTabs = tabs(restored)
        assertEquals("/opt/other/index.html", restoredTabs[0].url)
        assertEquals("/opt/other/src/A.kt", restoredTabs[1].filePath)
        assertEquals("/opt/other", restoredTabs[2].workingDirectory)
        assertEquals("cd ${CommandProcessor.quotePath("/opt/other")} && claude", restoredTabs[2].initialCommand)
        assertEquals("/opt/other", restored.projectPath)
        assertNotEquals("workspace-1", restored.id)
    }

    @Test
    fun `portable command inside a wider quote region uses the shared quote-context escaping`() {
        val portable =
            sampleWorkspace(projectPath).copy(
                layout =
                    SplitConfig.SinglePanel(
                        PanelConfig(
                            id = "main",
                            tabs =
                                listOf(
                                    TabConfig(
                                        type = "terminal",
                                        title = "T",
                                        initialCommand = "cd \"prefix$projectPath/sub\" && claude",
                                    ),
                                ),
                        ),
                    ),
            )

        val exported = WorkspacePortability.toPortable(portable)
        assertEquals(
            "cd \"prefix${WorkspacePortability.PLACEHOLDER}/sub\" && claude",
            tabs(exported).single().initialCommand,
        )

        val destination = "/opt/a\"b$(touch nope)"
        val restored = WorkspacePortability.fromPortable(exported, destination)
        assertEquals(
            "cd \"prefix${CommandProcessor.escapeInsideQuote(destination, '\"')}/sub\" && claude",
            tabs(restored).single().initialCommand,
        )
    }

    @Test
    fun `a Space with no project path is already portable and returned unchanged`() {
        val ws = sampleWorkspace(null)
        assertSame(ws, WorkspacePortability.toPortable(ws))
    }

    @Test
    fun `fromPortableJson returns null on invalid JSON`() {
        assertNull(WorkspacePortability.fromPortableJson("{ not json", "/opt/other"))
    }

    @Test
    fun `toPortableJson then fromPortableJson round-trips through serialization`() {
        val json = WorkspacePortability.toPortableJson(sampleWorkspace(projectPath))
        val restored = WorkspacePortability.fromPortableJson(json, projectPath)
        requireNotNull(restored)
        assertEquals(tabs(sampleWorkspace(projectPath)), tabs(restored))
        assertTrue(json.contains(WorkspacePortability.PLACEHOLDER))
    }
}
