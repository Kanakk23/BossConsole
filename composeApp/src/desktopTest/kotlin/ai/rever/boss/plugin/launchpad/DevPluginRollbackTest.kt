package ai.rever.boss.plugin.launchpad

import ai.rever.boss.cli.plugin.ValidatorTestFixturePlugin
import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.test.assertNotNull
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

    private class TestPluginContext(
        override val panelRegistry: PanelRegistry = PanelRegistry(),
        override val tabRegistry: TabRegistry = TabRegistry(),
        override val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : PluginContext

    private fun createManager(): DynamicPluginManager {
        val sandboxManager = PluginSandboxManagerImpl()
        val dummyContext = TestPluginContext()
        val manager =
            DynamicPluginManager(
                dummyContext.panelRegistry,
                dummyContext.tabRegistry,
                sandboxManager,
                createSandboxedContext = { _, _ -> dummyContext },
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

    private fun createJar(
        file: File,
        entries: Map<String, ByteArray>,
    ) {
        file.parentFile?.mkdirs()
        JarOutputStream(FileOutputStream(file)).use { jos ->
            entries.forEach { (name, bytes) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }

    private fun createDevTestJar(
        stagingRoot: File,
        pluginId: String,
        versionDir: String,
        version: String,
    ): File {
        val dir = File(stagingRoot, "$pluginId/$versionDir").apply { mkdirs() }
        val jar = File(dir, "$pluginId.jar")
        val classEntryPath = ValidatorTestFixturePlugin::class.java.name.replace('.', '/') + ".class"
        val classBytes =
            ValidatorTestFixturePlugin::class.java.classLoader
                .getResourceAsStream(classEntryPath)!!
                .readBytes()
        val manifest =
            """
            {
              "manifestVersion": 1,
              "pluginId": "$pluginId",
              "displayName": "Hot Reload Tool $version",
              "version": "$version",
              "apiVersion": "1.0.0",
              "mainClass": "${ValidatorTestFixturePlugin::class.java.name}"
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        createJar(
            jar,
            mapOf(
                "META-INF/boss-plugin/plugin.json" to manifest,
                classEntryPath to classBytes,
            ),
        )
        return jar
    }

    private fun capturePriorState(
        manager: DynamicPluginManager,
        pluginId: String,
    ): DevPluginReloader.PriorManagerState {
        val info = manager.getPluginInfo(pluginId)
        return DevPluginReloader.PriorManagerState(
            manager = manager,
            priorJarPath = info?.jarPath,
            wasLoaded = info?.state == PluginState.LOADED,
            wasEnabled = info?.enabled ?: true,
        )
    }

    private fun assertRestoredToV1(
        manager: DynamicPluginManager,
        pluginId: String,
        expectedJar: File,
    ) {
        val restored = manager.getPluginInfo(pluginId)
        assertNotNull(restored, "Manager must have a restored plugin after rollback")
        assertEquals(
            expectedJar.canonicalPath,
            File(restored.jarPath).canonicalPath,
            "Manager must have restored to v1.jar",
        )
        assertEquals(PluginState.LOADED, restored.state, "Restored plugin must be LOADED")
        assertTrue(restored.enabled, "Restored plugin must be enabled")
        assertEquals("1.0.0", restored.manifest.version, "Must be on v1 (1.0.0)")
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
    fun `hot reload failure in second manager restores prior jar in first manager`() =
        runBlocking {
            val pluginId = "com.example.hotreload"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")
            val v2Jar = createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")

            val manager1 = createManager()
            val manager2 = createManager()

            // 3. Manager 1 and Manager 2 start on v1.jar
            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must load v1.jar successfully")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res2.isSuccess, "Manager 2 must load v1.jar successfully")

            assertEquals(
                v1Jar.canonicalPath,
                File(manager1.getPluginInfo(pluginId)!!.jarPath).canonicalPath,
            )
            assertEquals(
                v1Jar.canonicalPath,
                File(manager2.getPluginInfo(pluginId)!!.jarPath).canonicalPath,
            )

            // 4. Capture prior states before reload cycle
            val priorStates =
                listOf(
                    capturePriorState(manager1, pluginId),
                    capturePriorState(manager2, pluginId),
                )

            // 5. Simulate reload progression:
            // Manager 1 unloads v1 and installs v2.jar successfully
            manager1.uninstallPlugin(pluginId, force = false, waitForGC = false)
            val v2InstallResult = manager1.installPlugin(v2Jar.absolutePath, enabled = true)
            assertTrue(v2InstallResult.isSuccess, "Manager 1 must succeed installing v2.jar")
            assertEquals(
                v2Jar.canonicalPath,
                File(manager1.getPluginInfo(pluginId)!!.jarPath).canonicalPath,
            )

            // Manager 2 unloads v1, but fails to install v2.jar (simulated install failure)
            manager2.uninstallPlugin(pluginId, force = false, waitForGC = false)
            assertNull(manager2.getPluginInfo(pluginId))

            // 6. Trigger symmetric rollback across managers
            DevPluginReloader.rollbackManagers(pluginId, priorStates)

            // 7. Assert that Manager 1 and Manager 2 uninstalled v2 and restored v1
            assertRestoredToV1(manager1, pluginId, v1Jar)
            assertRestoredToV1(manager2, pluginId, v1Jar)
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
