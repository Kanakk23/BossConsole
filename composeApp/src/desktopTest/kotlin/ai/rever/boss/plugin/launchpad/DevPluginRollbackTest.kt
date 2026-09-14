package ai.rever.boss.plugin.launchpad

import ai.rever.boss.cli.plugin.ValidatorTestFixturePlugin
import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.CanUnloadResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginUnloadAware
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.plugin.sandbox.SandboxConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private fun createManager(
        onSandboxedContext: ((pluginId: String, config: SandboxConfig) -> PluginContext)? = null,
    ): DynamicPluginManager {
        val sandboxManager = PluginSandboxManagerImpl()
        val dummyContext = TestPluginContext()
        val manager =
            DynamicPluginManager(
                dummyContext.panelRegistry,
                dummyContext.tabRegistry,
                sandboxManager,
                createSandboxedContext = { pluginId, config ->
                    onSandboxedContext?.invoke(pluginId, config) ?: dummyContext
                },
            )
        activeManagersToClean.add(manager)
        return manager
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
            }
        }
    }

    private fun createDevTestJar(
        stagingRoot: File,
        pluginId: String,
        versionDir: String,
        version: String,
        mainClass: String = ValidatorTestFixturePlugin::class.java.name,
        includeMainClassBytecode: Boolean = true,
    ): File {
        val dir = File(stagingRoot, "$pluginId/$versionDir").apply { mkdirs() }
        val jar = File(dir, "$pluginId.jar")
        val manifest =
            """
            {
              "manifestVersion": 1,
              "pluginId": "$pluginId",
              "displayName": "Hot Reload Tool $version",
              "version": "$version",
              "apiVersion": "1.0.0",
              "mainClass": "$mainClass"
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        val entries =
            mutableMapOf<String, ByteArray>(
                "META-INF/boss-plugin/plugin.json" to manifest,
            )
        if (includeMainClassBytecode) {
            val classEntryPath = mainClass.replace('.', '/') + ".class"
            val classBytes =
                ValidatorTestFixturePlugin::class.java.classLoader
                    .getResourceAsStream(classEntryPath)!!
                    .readBytes()
            entries[classEntryPath] = classBytes
        }
        createJar(jar, entries)
        return jar
    }

    private fun createStoreTestJar(
        storeJar: File,
        pluginId: String,
        version: String = "1.0.0",
    ) {
        val classEntryPath = ValidatorTestFixturePlugin::class.java.name.replace('.', '/') + ".class"
        val classBytes =
            ValidatorTestFixturePlugin::class.java.classLoader
                .getResourceAsStream(classEntryPath)!!
                .readBytes()
        val storeManifest =
            """
            {
              "manifestVersion": 1,
              "pluginId": "$pluginId",
              "displayName": "Store Build $version",
              "version": "$version",
              "apiVersion": "1.0.0",
              "mainClass": "${ValidatorTestFixturePlugin::class.java.name}"
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        createJar(
            storeJar,
            mapOf(
                "META-INF/boss-plugin/plugin.json" to storeManifest,
                classEntryPath to classBytes,
            ),
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

    private fun assertRestoredToV1Disabled(
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
        assertEquals(PluginState.DISABLED, restored.state, "Restored plugin must be DISABLED")
        assertFalse(restored.enabled, "Restored plugin must be disabled")
        assertEquals("1.0.0", restored.manifest.version, "Must be on v1 (1.0.0)")
    }

    @Test
    fun `first link failure uninstalls partially installed plugin across managers to restore clean initial state`() =
        runBlocking {
            val pluginId = "first-link-tool"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var manager2FailLink = true
            val manager2 =
                createManager { _, _ ->
                    if (manager2FailLink) {
                        manager2FailLink = false
                        error("Simulated link failure in manager 2")
                    }
                    dummyContext
                }

            assertNull(manager1.getPluginInfo(pluginId))
            assertNull(manager2.getPluginInfo(pluginId))

            // Drive through public DevPluginReloader.reload
            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when second manager fails install")

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

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var manager2FailV2Install = false
            val manager2 =
                createManager { _, _ ->
                    if (manager2FailV2Install) {
                        manager2FailV2Install = false
                        error("Simulated v2 install failure on manager 2")
                    }
                    dummyContext
                }

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

            // Stage v2.jar and arm manager 2 to fail v2 install
            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            manager2FailV2Install = true

            // Drive through public DevPluginReloader.reload
            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when manager 2 fails v2 install")

            assertRestoredToV1(manager1, pluginId, v1Jar)
            assertRestoredToV1(manager2, pluginId, v1Jar)
        }

    @Test
    fun `hot reload failure in second manager restores disabled prior jar in first manager with wasEnabled false`() =
        runBlocking {
            val pluginId = "com.example.disabled.rollback"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var manager2FailV2Install = false
            val manager2 =
                createManager { _, _ ->
                    if (manager2FailV2Install) {
                        manager2FailV2Install = false
                        error("Simulated v2 install failure on manager 2")
                    }
                    dummyContext
                }

            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = false)
            assertTrue(res1.isSuccess, "Manager 1 must load v1.jar successfully")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = false)
            assertTrue(res2.isSuccess, "Manager 2 must load v1.jar successfully")

            assertEquals(PluginState.DISABLED, manager1.getPluginInfo(pluginId)!!.state)
            assertFalse(manager1.getPluginInfo(pluginId)!!.enabled)

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            manager2FailV2Install = true

            // Drive through public DevPluginReloader.reload
            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when manager 2 fails v2 install")

            assertRestoredToV1Disabled(manager1, pluginId, v1Jar)
            assertRestoredToV1Disabled(manager2, pluginId, v1Jar)
        }

    @Test
    fun `reload with late refusal leaves refusing manager untouched`() =
        runBlocking {
            val pluginId = "com.example.laterefusal"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val manager1 = createManager()
            val manager2 = createManager()

            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must install v1.jar")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res2.isSuccess, "Manager 2 must install v1.jar")

            val initialManager2Info = manager2.getPluginInfo(pluginId)
            assertNotNull(initialManager2Info)

            var manager1Unloaded = false
            manager1.registerUnloadAware(
                object : PluginUnloadAware {
                    override fun checkCanUnload(pluginId: String): CanUnloadResult = CanUnloadResult.Ok

                    override fun prepareForUnload(pluginId: String) {
                        manager1Unloaded = true
                    }
                },
            )
            manager2.registerUnloadAware(
                object : PluginUnloadAware {
                    override fun checkCanUnload(pluginId: String): CanUnloadResult =
                        if (!manager1Unloaded) {
                            CanUnloadResult.Ok
                        } else {
                            CanUnloadResult.NotAllowed(listOf("Late refusal from manager 2"))
                        }

                    override fun prepareForUnload(pluginId: String) {
                        // No preparation needed; manager doesn't hold unloadable resources
                    }
                },
            )

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")

            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when manager 2 refuses unload")

            // Manager 1 had unloaded, so rollback cleanly restores its prior v1.jar
            assertRestoredToV1(manager1, pluginId, v1Jar)

            // Manager 2 refused unload, so it was never modified; rollback leaves it completely untouched
            assertRestoredToV1(manager2, pluginId, v1Jar)
            val currentManager2Info = manager2.getPluginInfo(pluginId)
            assertNotNull(currentManager2Info)
            assertEquals(
                initialManager2Info.loadedAt,
                currentManager2Info.loadedAt,
                "Manager 2 was untouched and must retain its exact original loaded instance",
            )
        }

    @Test
    fun `startup fallback loads store jar when staged dev jar fails`() =
        runBlocking {
            val pluginId = "com.example.startup.fallback"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val storeDir = tempDir.resolve("store-builds").toFile().apply { mkdirs() }
            val storeJar = File(storeDir, "$pluginId.jar")
            createStoreTestJar(storeJar, pluginId, "1.0.0")

            // Create a dev jar that passes archive and manifest validation, but points to a non-existent mainClass
            createDevTestJar(
                stagingRoot = stagingRoot,
                pluginId = pluginId,
                versionDir = "v2000",
                version = "2.0.0",
                mainClass = "non.existent.BrokenPluginMain",
                includeMainClassBytecode = false,
            )

            val manager = createManager()
            val storeEntry =
                PluginPersistence.InstalledPluginEntry(
                    pluginId = pluginId,
                    jarPath = storeJar.absolutePath,
                    enabled = true,
                )

            val results =
                PluginStoreSetup.loadPersistedPluginEntries(
                    dynamicPluginManager = manager,
                    persistedPlugins = listOf(storeEntry),
                    devRoot = stagingRoot,
                )

            val pluginResult = results[pluginId]
            assertNotNull(pluginResult, "Must have a result for plugin")
            assertTrue(
                pluginResult.isSuccess,
                "Plugin must successfully fall back to store build: ${pluginResult.exceptionOrNull()}",
            )

            val loadedInfo = manager.getPluginInfo(pluginId)
            assertNotNull(loadedInfo, "Plugin must be loaded in manager")
            assertEquals(storeJar.canonicalPath, File(loadedInfo.jarPath).canonicalPath, "Must load store jar path")
            assertEquals(PluginState.LOADED, loadedInfo.state, "Fallback plugin must be LOADED")
            assertEquals("1.0.0", loadedInfo.manifest.version, "Must be on store version 1.0.0")
        }

    @Test
    fun `reload of disabled plugin preserves disabled state across managers without throwing`() =
        runBlocking {
            val pluginId = "com.example.disabled.reload"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val manager1 = createManager()
            val manager2 = createManager()

            // 1. Both managers start with v1.jar installed and DISABLED
            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = false)
            assertTrue(res1.isSuccess, "Manager 1 must install v1.jar")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = false)
            assertTrue(res2.isSuccess, "Manager 2 must install v1.jar")

            assertEquals(PluginState.DISABLED, manager1.getPluginInfo(pluginId)?.state)
            assertFalse(manager1.getPluginInfo(pluginId)!!.enabled)

            // 2. Stage v2.jar
            val v2Jar = createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")

            // 3. Perform full DevPluginReloader.reload
            val reloadResult = DevPluginReloader.reload(pluginId, stagingRoot)
            val errorMsg = reloadResult.exceptionOrNull()?.message
            assertTrue(reloadResult.isSuccess, "Reload of disabled plugin must succeed: $errorMsg")

            // 4. Verify both managers updated to v2.jar and remain DISABLED
            val info1 = manager1.getPluginInfo(pluginId)
            assertNotNull(info1)
            assertEquals(File(v2Jar.absolutePath).canonicalPath, File(info1.jarPath).canonicalPath)
            assertEquals(PluginState.DISABLED, info1.state)
            assertFalse(info1.enabled, "Manager 1 must preserve disabled state")
            assertEquals("2.0.0", info1.manifest.version)

            val info2 = manager2.getPluginInfo(pluginId)
            assertNotNull(info2)
            assertEquals(File(v2Jar.absolutePath).canonicalPath, File(info2.jarPath).canonicalPath)
            assertEquals(PluginState.DISABLED, info2.state)
            assertFalse(info2.enabled, "Manager 2 must preserve disabled state")
            assertEquals("2.0.0", info2.manifest.version)
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

    @Test
    fun `partial install failure on window 1 leaves window 2 untouched`() =
        runBlocking {
            val pluginId = "com.example.partial.install"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val dummyContext = TestPluginContext()
            var manager1FailInstall = false
            val manager1 =
                createManager { _, _ ->
                    if (manager1FailInstall) {
                        manager1FailInstall = false
                        error("Simulated v2 install failure on manager 1")
                    }
                    dummyContext
                }
            val manager2 = createManager()

            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must install v1.jar")
            assertNull(manager2.getPluginInfo(pluginId), "Manager 2 must have no plugin")

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            manager1FailInstall = true

            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when manager 1 fails install")

            assertRestoredToV1(manager1, pluginId, v1Jar)
            assertNull(manager2.getPluginInfo(pluginId), "Manager 2 was untouched and remains null")
        }

    @Test
    fun `startup fallback recovers store jar on binary incompatibility`() =
        runBlocking {
            val pluginId = "com.example.binary.compat"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val storeDir = tempDir.resolve("store-compat").toFile().apply { mkdirs() }
            val storeJar = File(storeDir, "$pluginId.jar")
            createStoreTestJar(storeJar, pluginId, "1.0.0")

            createDevTestJar(
                stagingRoot = stagingRoot,
                pluginId = pluginId,
                versionDir = "v2000",
                version = "2.0.0",
                mainClass = IncompatibleBinaryPlugin::class.java.name,
                includeMainClassBytecode = true,
            )

            val manager = createManager()
            val storeEntry =
                PluginPersistence.InstalledPluginEntry(
                    pluginId = pluginId,
                    jarPath = storeJar.absolutePath,
                    enabled = true,
                )

            val results =
                PluginStoreSetup.loadPersistedPluginEntries(
                    dynamicPluginManager = manager,
                    persistedPlugins = listOf(storeEntry),
                    devRoot = stagingRoot,
                )

            val pluginResult = results[pluginId]
            assertNotNull(pluginResult, "Must have a result for plugin")
            assertTrue(
                pluginResult.isSuccess,
                "Plugin must fall back to store build on binary incompatibility: ${pluginResult.exceptionOrNull()}",
            )

            val loadedInfo = manager.getPluginInfo(pluginId)
            assertNotNull(loadedInfo, "Plugin must be loaded in manager")
            assertEquals(storeJar.canonicalPath, File(loadedInfo.jarPath).canonicalPath)
            assertEquals(PluginState.LOADED, loadedInfo.state)
            assertEquals("1.0.0", loadedInfo.manifest.version)
        }

    @Test
    fun `external scan falls back to standard jar on dev failure`() =
        runBlocking {
            val pluginId = "com.example.ext.fallback"
            val pluginDir = tempDir.resolve("ext-plugins").toFile().apply { mkdirs() }
            val standardJar = File(pluginDir, "$pluginId.jar")
            createStoreTestJar(standardJar, pluginId, "1.0.0")

            val devRoot = File(pluginDir, "dev").apply { mkdirs() }
            DevPluginArtifacts.stagingRootOverride = devRoot
            try {
                val devJar =
                    createDevTestJar(
                        stagingRoot = devRoot,
                        pluginId = pluginId,
                        versionDir = "v2000",
                        version = "2.0.0",
                        mainClass = "non.existent.BrokenClass",
                        includeMainClassBytecode = false,
                    )

                val manager = createManager()
                val defaultPlugin =
                    DefaultPlugin(
                        panelRegistry = PanelRegistry(),
                        tabRegistry = TabRegistry(),
                        windowProjectState = null,
                    )

                defaultPlugin.installSingleExternalPlugin(
                    manager = manager,
                    jarFile = devJar,
                    trackedJarPaths = emptySet(),
                    fallbackStandardJar = standardJar,
                )

                val loaded = manager.getPluginInfo(pluginId)
                assertNotNull(loaded, "Manager must load fallback standard plugin")
                assertEquals(standardJar.canonicalPath, File(loaded.jarPath).canonicalPath)
                assertEquals(PluginState.LOADED, loaded.state)
                assertEquals("1.0.0", loaded.manifest.version)
            } finally {
                DevPluginArtifacts.stagingRootOverride = null
            }
        }
}

class IncompatibleBinaryPlugin : ai.rever.boss.plugin.api.Plugin {
    override val pluginId = "com.example.binary.compat"
    override val displayName = "Incompatible Binary Plugin"

    override fun register(context: PluginContext) = throw NoSuchMethodError("simulated binary incompatibility")
}
