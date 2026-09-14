package ai.rever.boss.plugin.launchpad

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ai.rever.boss.plugin.api.PluginManifest as ApiPluginManifest

class DevPluginRollbackTest {
    @TempDir
    lateinit var tempDir: Path

    private val activeManagersToClean = mutableListOf<DynamicPluginManager>()

    @BeforeTest
    fun setUp() {
        DevPluginArtifacts.stagingRootOverride = tempDir.resolve("dev-root").toFile().apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            activeManagersToClean.forEach {
                runCatching { it.disposeWindow() }
            }
            activeManagersToClean.clear()
        }
        DevPluginArtifacts.stagingRootOverride = null
    }

    private fun createManager(): DynamicPluginManager {
        val sandboxManager = PluginSandboxManagerImpl()
        val manager =
            DynamicPluginManager(
                PanelRegistry(),
                TabRegistry(),
                sandboxManager,
                createSandboxedContext = { _, _ -> error("Test fixture context") },
            )
        activeManagersToClean.add(manager)
        return manager
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPluginInfo(
        manager: DynamicPluginManager,
        pluginId: String,
        info: DynamicPluginInfo,
    ) {
        val field = DynamicPluginManager::class.java.getDeclaredField("_pluginStates").apply { isAccessible = true }
        val flow = field.get(manager) as MutableStateFlow<Map<String, DynamicPluginInfo>>
        flow.value = flow.value + (pluginId to info)
    }

    private fun createJar(file: File, entries: Map<String, ByteArray>) {
        file.parentFile?.mkdirs()
        JarOutputStream(FileOutputStream(file)).use { jos ->
            entries.forEach { (name, bytes) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }

    @Test
    fun `first link failure uninstalls partially installed plugin across managers to restore clean initial state`() =
        runBlocking {
            val pluginId = "first-link-tool"
            val manager1 = createManager()
            val manager2 = createManager()

            // Simulate manager1 having newly loaded the plugin during the link cycle
            val dummyManifest =
                ApiPluginManifest(
                    pluginId = pluginId,
                    displayName = "First Link Tool",
                    version = "1.0.0",
                    apiVersion = "1.0.0",
                    mainClass = "test.FirstLinkMain",
                    canUnload = true,
                )
            val info =
                DynamicPluginInfo(
                    manifest = dummyManifest,
                    jarPath = "/fake/v1/first-link-tool.jar",
                    state = PluginState.LOADED,
                    loadedAt = System.currentTimeMillis(),
                    enabled = true,
                )
            setPluginInfo(manager1, pluginId, info)

            // Prior state: plugin was NOT previously loaded in either manager
            val priorStates =
                listOf(
                    DevPluginReloader.PriorManagerState(
                        manager = manager1,
                        priorJarPath = null,
                        wasLoaded = false,
                        wasEnabled = true,
                    ),
                    DevPluginReloader.PriorManagerState(
                        manager = manager2,
                        priorJarPath = null,
                        wasLoaded = false,
                        wasEnabled = true,
                    ),
                )

            // Rollback must uninstall from manager1 to avoid leaving a fractured single-window install
            DevPluginReloader.rollbackManagers(pluginId, priorStates)

            assertNull(
                manager1.getPluginInfo(pluginId),
                "Manager 1 must have uninstalled the newly linked plugin after rollback",
            )
            assertNull(
                manager2.getPluginInfo(pluginId),
                "Manager 2 must remain in clean state without plugin",
            )
        }

    @Test
    fun `isValidDevJar validates readable zip and manifest content`() {
        val validJar = tempDir.resolve("valid.jar").toFile()
        val manifestJson = """{"id": "test-plugin", "version": "1.0.0"}""".toByteArray()
        createJar(validJar, mapOf("META-INF/boss-plugin/plugin.json" to manifestJson))

        assertTrue(DevPluginArtifacts.isValidDevJar(validJar, "test-plugin"))
        assertTrue(DevPluginArtifacts.isValidDevJar(validJar, null))
        assertFalse(DevPluginArtifacts.isValidDevJar(validJar, "mismatched-id"))

        // Corrupt zip (random bytes)
        val corruptJar = tempDir.resolve("corrupt.jar").toFile()
        corruptJar.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        assertFalse(DevPluginArtifacts.isValidDevJar(corruptJar))

        // Empty file
        val emptyJar = tempDir.resolve("empty.jar").toFile()
        emptyJar.writeBytes(byteArrayOf())
        assertFalse(DevPluginArtifacts.isValidDevJar(emptyJar))

        // Missing manifest entry
        val noManifestJar = tempDir.resolve("no-manifest.jar").toFile()
        createJar(noManifestJar, mapOf("dummy.txt" to "hello".toByteArray()))
        assertFalse(DevPluginArtifacts.isValidDevJar(noManifestJar))

        // Part file
        val partJar = tempDir.resolve("plugin.jar.part").toFile()
        createJar(partJar, mapOf("META-INF/boss-plugin/plugin.json" to manifestJson))
        assertFalse(DevPluginArtifacts.isValidDevJar(partJar))
    }

    @Test
    fun `findAllActiveDevJars with deepValidate filters out invalid dev jars`() {
        val devRoot = tempDir.resolve("staging-test").toFile().apply { mkdirs() }

        // Corrupt dev jar under plugin-corrupt
        val corruptDir = File(devRoot, "plugin-corrupt/v1000").apply { mkdirs() }
        File(corruptDir, "plugin-corrupt.jar").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        // Valid dev jar under plugin-valid
        val validDir = File(devRoot, "plugin-valid/v2000").apply { mkdirs() }
        val manifestBytes = """{"id": "plugin-valid", "version": "1.0.0"}""".toByteArray()
        val validJar = File(validDir, "plugin-valid.jar")
        createJar(validJar, mapOf("META-INF/boss-plugin/plugin.json" to manifestBytes))

        val allDiscovered = DevPluginArtifacts.findAllActiveDevJars(devRoot, deepValidate = true)
        assertEquals(1, allDiscovered.size)
        assertEquals(validJar.absolutePath, allDiscovered.first().absolutePath)
    }
}
