package ai.rever.boss.plugin.packs

import ai.rever.boss.components.plugin.DependencyInstallPlan
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPluginPackEffectsClosureTest {
    private class FakeRepo(
        private val plugins: Map<String, PluginInfo?> = emptyMap(),
        private val versions: Map<String, List<PluginInfo>> = emptyMap(),
    ) : PluginRepository {
        override val id: String = "fake"
        override val name: String = "Fake"
        override val isLocal: Boolean = false
        override val isAvailable: Boolean = true

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
            if (pluginId in plugins) {
                Result.success(plugins[pluginId])
            } else {
                Result.failure(NoSuchElementException("Plugin $pluginId not found"))
            }

        override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> = Result.success(versions[pluginId].orEmpty())

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> = error("unused")

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?,
        ): Result<String> = error("unused")

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    }

    private class FakeInstaller(
        private val plan: DependencyInstallPlan,
    ) : MissingDependencyInstaller {
        override fun isInstalled(pluginId: String): Boolean = false

        override suspend fun displayNameFor(pluginId: String): String? = null

        override suspend fun planFor(pluginId: String): DependencyInstallPlan = plan

        override suspend fun install(pluginId: String): Result<Unit> = error("unused")
    }

    @Test
    fun `closureFor marks members as unresolved when store lookup fails`() =
        runBlocking<Unit> {
            val plan =
                DependencyInstallPlan(
                    order = listOf("dep.failed", "root.ok"),
                    unresolved = emptySet(),
                    cyclic = false,
                    truncated = false,
                )
            val installer = FakeInstaller(plan)
            val repo =
                FakeRepo(
                    plugins =
                        mapOf(
                            "root.ok" to PluginInfo("root.ok", displayName = "Root", version = "1.0.0", sha256 = "sha-root"),
                            // dep.failed is omitted, simulating a lookup failure
                        ),
                )

            val closure = closureFor(installer, "root.ok", repo)
            assertTrue("dep.failed" in closure.unresolved, "Failed lookups must be recorded in unresolved")
            assertEquals(1, closure.artifacts.size)
            assertEquals("root.ok", closure.artifacts[0].pluginId)
        }

    @Test
    fun `closureFor marks members as unresolved when store hash is blank`() =
        runBlocking<Unit> {
            val plan =
                DependencyInstallPlan(
                    order = listOf("dep.nohash", "root.ok"),
                    unresolved = emptySet(),
                    cyclic = false,
                    truncated = false,
                )
            val installer = FakeInstaller(plan)
            val repo =
                FakeRepo(
                    plugins =
                        mapOf(
                            "root.ok" to PluginInfo("root.ok", displayName = "Root", version = "1.0.0", sha256 = "sha-root"),
                            "dep.nohash" to PluginInfo("dep.nohash", displayName = "Dep", version = "2.0.0", sha256 = ""),
                        ),
                    versions =
                        mapOf(
                            "dep.nohash" to listOf(PluginInfo("dep.nohash", displayName = "Dep", version = "2.0.0", sha256 = "")),
                        ),
                )

            val closure = closureFor(installer, "root.ok", repo)
            assertTrue("dep.nohash" in closure.unresolved, "Blank hashes must be recorded in unresolved")
            assertEquals(1, closure.artifacts.size)
            assertEquals("root.ok", closure.artifacts[0].pluginId)
        }

    @Test
    fun `closureFor resolves artifacts with non-blank hash when store has valid info`() =
        runBlocking<Unit> {
            val plan =
                DependencyInstallPlan(
                    order = listOf("dep.ok", "root.ok"),
                    unresolved = emptySet(),
                    cyclic = false,
                    truncated = false,
                )
            val installer = FakeInstaller(plan)
            val repo =
                FakeRepo(
                    plugins =
                        mapOf(
                            "root.ok" to PluginInfo("root.ok", displayName = "Root", version = "1.0.0", sha256 = "sha-root"),
                            "dep.ok" to PluginInfo("dep.ok", displayName = "Dep", version = "2.0.0", sha256 = "sha-dep"),
                        ),
                )

            val closure = closureFor(installer, "root.ok", repo)
            assertTrue(closure.unresolved.isEmpty(), "No unresolved dependencies expected")
            assertEquals(2, closure.artifacts.size)
            val depArtifact = closure.artifacts.first { it.pluginId == "dep.ok" }
            assertEquals("2.0.0", depArtifact.version)
            assertEquals("sha-dep", depArtifact.sha256)
            val rootArtifact = closure.artifacts.first { it.pluginId == "root.ok" }
            assertEquals("1.0.0", rootArtifact.version)
            assertEquals("sha-root", rootArtifact.sha256)
        }
}
