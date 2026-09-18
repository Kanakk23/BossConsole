package ai.rever.boss.settings

import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.plugin.browser.BrowserSettings
import ai.rever.boss.plugin.browser.BrowserSettingsData
import ai.rever.boss.plugin.browser.BrowserSettingsManager
import ai.rever.boss.window.WindowAppearanceSettings
import ai.rever.boss.window.WindowAppearanceSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies atomic persistence and concurrency safety for desktop settings managers:
 * - [WindowAppearanceSettingsManager]
 * - [BrowserSettingsManager]
 * - [KeymapSettingsManager]
 *
 * Ensures that writes utilize atomic replacement so files are never partially written,
 * temp swap files do not linger, and concurrent saves do not corrupt settings.
 */
class DesktopSettingsAtomicWriteTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var originalWindowAppearanceFile: File
    private lateinit var originalBrowserFile: File
    private lateinit var originalKeymapFile: File

    @BeforeEach
    fun setUp() {
        originalWindowAppearanceFile = WindowAppearanceSettingsManager.settingsFile
        originalBrowserFile = BrowserSettingsManager.settingsFile
        originalKeymapFile = KeymapSettingsManager.settingsFile

        WindowAppearanceSettingsManager.settingsFile = File(tempDir, "window-appearance-settings.json")
        BrowserSettingsManager.settingsFile = File(tempDir, "browser-settings.json")
        KeymapSettingsManager.settingsFile = File(tempDir, "keymap-settings.json")
    }

    @AfterEach
    fun tearDown() {
        WindowAppearanceSettingsManager.settingsFile = originalWindowAppearanceFile
        BrowserSettingsManager.settingsFile = originalBrowserFile
        KeymapSettingsManager.settingsFile = originalKeymapFile
    }

    @Test
    fun `window appearance settings updates persist atomically and cleanly`() =
        runBlocking {
            val targetFile = WindowAppearanceSettingsManager.settingsFile
            val newSettings =
                WindowAppearanceSettings(
                    showTitleBar = true,
                    showLeftStrip = false,
                    showRightStrip = true,
                )

            WindowAppearanceSettingsManager.updateSettings(newSettings)

            assertTrue(targetFile.exists(), "Settings file must exist after save")
            val content = targetFile.readText()
            val decoded = WindowAppearanceSettingsManager.json.decodeFromString<WindowAppearanceSettings>(content)
            assertEquals(newSettings.showTitleBar, decoded.showTitleBar)
            assertEquals(newSettings.showRightStrip, decoded.showRightStrip)

            // Ensure no stray .tmp files exist in parent directory
            val strayTmpFiles = tempDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
            assertEquals(0, strayTmpFiles.size, "No stray .tmp files should be left behind")
        }

    @Test
    fun `concurrent window appearance saves do not corrupt json`() =
        runBlocking {
            val iterations = 25
            val jobs =
                (1..iterations).map { i ->
                    async(Dispatchers.IO) {
                        WindowAppearanceSettingsManager.updateSettings(
                            WindowAppearanceSettings(showTitleBar = (i % 2 == 0)),
                        )
                    }
                }
            jobs.awaitAll()

            val content = WindowAppearanceSettingsManager.settingsFile.readText()
            // Must decode without throwing SerializationException
            val decoded = WindowAppearanceSettingsManager.json.decodeFromString<WindowAppearanceSettings>(content)
            assertNotNull(decoded)
        }

    @Test
    fun `browser settings saves persist atomically and cleanly`() =
        runBlocking {
            val targetFile = BrowserSettingsManager.settingsFile
            BrowserSettings.warnForExecutables = false
            BrowserSettings.showShareButton = true

            BrowserSettingsManager.saveSettings()

            assertTrue(targetFile.exists(), "Browser settings file must exist after save")
            val content = targetFile.readText()
            val decoded = WindowAppearanceSettingsManager.json.decodeFromString<BrowserSettingsData>(content)
            assertFalse(decoded.warnForExecutables)
            assertTrue(decoded.showShareButton)

            val strayTmpFiles = tempDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
            assertEquals(0, strayTmpFiles.size, "No stray .tmp files should remain")
        }

    @Test
    fun `keymap settings export to file writes atomically`() =
        runBlocking {
            val exportFile = File(tempDir, "exported-keymap.json")
            KeymapSettingsManager.exportToFile(exportFile)

            assertTrue(exportFile.exists(), "Export file must exist")
            val jsonString = exportFile.readText()
            val imported = KeymapSettingsManager.importFromJson(jsonString)
            assertNotNull(imported, "Exported JSON must be valid and re-importable")

            val strayTmpFiles = tempDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
            assertEquals(0, strayTmpFiles.size, "No temporary files should linger")
        }

    @Test
    fun `concurrent keymap updates maintain valid json on disk`() =
        runBlocking {
            val baseSettings = KeymapPresets.getBOSSDefault()
            val iterations = 20
            val jobs =
                (1..iterations).map { i ->
                    async(Dispatchers.IO) {
                        KeymapSettingsManager.updateSettings(
                            baseSettings.copy(presetName = "Preset-$i"),
                        )
                    }
                }
            jobs.awaitAll()

            val content = KeymapSettingsManager.settingsFile.readText()
            val decoded = WindowAppearanceSettingsManager.json.decodeFromString<ai.rever.boss.keymap.model.KeymapSettings>(content)
            assertNotNull(decoded)
            assertTrue(decoded.presetName.startsWith("Preset-"))
        }
}
