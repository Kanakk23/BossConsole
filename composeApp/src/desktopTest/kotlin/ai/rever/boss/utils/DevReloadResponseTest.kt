package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class DevReloadResponseTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun useTempRuntimeDir() {
        SingleInstanceManager.runtimeDirOverride = tempDir.resolve("run").toFile()
    }

    @AfterEach
    fun releaseChannel() {
        SingleInstanceManager.release()
        SingleInstanceManager.runtimeDirOverride = null
    }

    @Test
    fun `reload response warns when restoring the previous build also failed`() {
        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            throw IllegalStateException("new build failed").apply {
                addSuppressed(IllegalStateException("old build could not be restored"))
            }
        }
        assertTrue(SingleInstanceManager.acquireLock())
        val result = SingleInstanceManager.reloadDevPlugin("diagnostic-plugin")
        kotlin.test.assertIs<ReloadResult.Failed>(result)
        assertTrue(result.reason.contains("Rollback failed"), result.reason)
        assertTrue(result.reason.contains("new build failed"), result.reason)
    }
}
