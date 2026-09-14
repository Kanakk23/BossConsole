package ai.rever.boss.plugin.launchpad

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.HotReloadPolicy
import ai.rever.boss.plugin.api.CanUnloadResult
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes dev plugin hot-reload by dispatching unload and install requests directly to DynamicPluginManager.
 */
@Suppress("ReturnCount", "ThrowsCount", "TooGenericExceptionCaught")
object DevPluginReloader {
    private val logger = BossLogger.forComponent("DevPluginReloader")

    /**
     * Reloads the active dev build of [pluginId] into all running host [DynamicPluginManager] instances.
     * Preserves native/browser restart policies, runs a dry-run pre-flight check across all managers
     * before unloading, unloads running instances without force, loads fresh bytes, and prunes old
     * staging directories on the host while strictly retaining any JAR path actively referenced by any manager.
     * Executes strictly on [Dispatchers.Main] to prevent UI hierarchy threading deadlocks.
     *
     * The unload deliberately does not wait for classloader garbage collection: the poll loop
     * runs [System.gc] on the caller's thread, and running it here would freeze the UI for up
     * to the GC watcher's timeout. Every host reload path takes the same default; the old
     * loader is reclaimed out of band once nothing references it.
     */
    suspend fun reload(
        pluginId: String,
        devRoot: File = DevPluginArtifacts.stagingRoot(),
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val activeManagers = DynamicPluginManager.activeManagers()
                if (activeManagers.isEmpty()) {
                    error("Host plugin manager is not yet initialized")
                }

                if (HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId)) {
                    val message = "Plugin $pluginId owns native resources that require a full application restart"
                    logger.warn(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId))
                    error(message)
                }

                val stagedJar =
                    DevPluginArtifacts.findActiveDevJar(pluginId, devRoot)
                        ?: error("No staged dev JAR found for plugin $pluginId in ${devRoot.absolutePath}")

                logger.info(
                    LogCategory.SYSTEM,
                    "Initiating dev plugin reload",
                    mapOf(
                        "pluginId" to pluginId,
                        "jarPath" to stagedJar.absolutePath,
                        "managersCount" to activeManagers.size,
                    ),
                )

                val priorStates =
                    activeManagers.map { manager ->
                        val info = manager.getPluginInfo(pluginId)
                        PriorManagerState(
                            manager = manager,
                            priorJarPath = info?.jarPath,
                            wasLoaded = info?.state == PluginState.LOADED,
                            wasEnabled = info?.enabled ?: true,
                        )
                    }

                preflightCheck(pluginId, activeManagers)
                try {
                    unloadManagers(pluginId, activeManagers)
                    installManagers(pluginId, stagedJar, activeManagers)
                } catch (e: Exception) {
                    rollbackManagers(pluginId, priorStates)
                    throw e
                }
                pruneStaging(pluginId, devRoot)

                logger.info(
                    LogCategory.SYSTEM,
                    "Dev plugin reloaded successfully",
                    mapOf("pluginId" to pluginId, "jarPath" to stagedJar.absolutePath),
                )
            }
        }

    internal data class PriorManagerState(
        val manager: DynamicPluginManager,
        val priorJarPath: String?,
        val wasLoaded: Boolean,
        val wasEnabled: Boolean,
    )

    internal suspend fun rollbackManagers(
        pluginId: String,
        priorStates: List<PriorManagerState>,
    ) {
        logger.warn(
            LogCategory.SYSTEM,
            "Rolling back dev plugin reload across managers",
            mapOf("pluginId" to pluginId, "managersCount" to priorStates.size),
        )
        for (priorState in priorStates) {
            rollbackSingleManager(pluginId, priorState)
        }
    }

    private suspend fun rollbackSingleManager(
        pluginId: String,
        priorState: PriorManagerState,
    ) {
        try {
            val hasValidPriorJar =
                priorState.wasLoaded &&
                    priorState.priorJarPath != null &&
                    File(priorState.priorJarPath).exists()
            if (hasValidPriorJar) {
                // Hot-reload case: reinstall the prior working JAR with previous enabled state
                if (priorState.manager.getPluginInfo(pluginId) != null) {
                    priorState.manager.uninstallPlugin(pluginId, force = true, waitForGC = false)
                }
                priorState.manager.installPlugin(priorState.priorJarPath, enabled = priorState.wasEnabled)
            } else {
                // First link case: was newly installed during this reload cycle;
                // uninstall it to restore clean initial state
                if (priorState.manager.getPluginInfo(pluginId) != null) {
                    priorState.manager.uninstallPlugin(pluginId, force = true, waitForGC = false)
                }
            }
        } catch (e: Exception) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to rollback plugin $pluginId on manager",
                mapOf("pluginId" to pluginId),
                e,
            )
        }
    }

    private suspend fun preflightCheck(
        pluginId: String,
        managers: List<DynamicPluginManager>,
    ) {
        for (manager in managers) {
            val info = manager.getPluginInfo(pluginId) ?: continue
            if (manager.isSystemPlugin(pluginId) || info.manifest.canUnload == false) {
                val message = "Plugin '$pluginId' is a protected system plugin"
                logger.warn(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId))
                error(message)
            }
            val canUnloadResult = manager.checkCanUnload(pluginId)
            if (canUnloadResult is CanUnloadResult.NotAllowed) {
                val reasons = canUnloadResult.reasons.joinToString(", ")
                val message = "Cannot unload '$pluginId' due to active dependents: $reasons"
                logger.warn(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId, "reasons" to reasons))
                error(message)
            }
        }
    }

    private suspend fun unloadManagers(
        pluginId: String,
        managers: List<DynamicPluginManager>,
    ) {
        for (manager in managers) {
            if (manager.getPluginInfo(pluginId) != null) {
                val unloadResult = manager.uninstallPlugin(pluginId, force = false, waitForGC = false)
                if (unloadResult.isFailure) {
                    val error = unloadResult.exceptionOrNull()
                    val message = "Failed to unload plugin $pluginId: ${error?.message ?: "unknown"}"
                    logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                    throw IllegalStateException(message, error)
                }
            }
        }
    }

    private suspend fun installManagers(
        pluginId: String,
        stagedJar: File,
        managers: List<DynamicPluginManager>,
    ) {
        for (manager in managers) {
            val installResult = manager.installPlugin(stagedJar.absolutePath, enabled = true)
            val installed = installResult.getOrNull()
            if (installed == null || installed.state != PluginState.LOADED) {
                val error = installResult.exceptionOrNull()
                val state = installed?.state?.name ?: "unknown"
                val message =
                    "Staged dev JAR for $pluginId is not running (state: $state): " +
                        "${error?.message ?: "installation did not report a loaded plugin"}"
                logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                throw IllegalStateException(message, error)
            }
        }
    }

    private fun pruneStaging(
        pluginId: String,
        devRoot: File,
    ) {
        val activeJarPaths =
            DynamicPluginManager
                .activeManagers()
                .mapNotNull { it.getPluginInfo(pluginId)?.jarPath }
                .toSet()
        val pluginDevDir = DevPluginArtifacts.pluginDevDir(pluginId, devRoot)
        DevPluginArtifacts.pruneStagingHistory(
            pluginDevDir = pluginDevDir,
            activeJarPaths = activeJarPaths,
        )
    }
}
