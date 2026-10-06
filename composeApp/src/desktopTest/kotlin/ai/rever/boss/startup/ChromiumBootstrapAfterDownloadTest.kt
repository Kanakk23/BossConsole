package ai.rever.boss.startup

import ai.rever.boss.startup.ChromiumBootstrap.AfterDownload
import ai.rever.boss.startup.ChromiumBootstrap.afterDownloadAction
import kotlin.test.Test
import kotlin.test.assertEquals

class ChromiumBootstrapAfterDownloadTest {
    @Test
    fun packagedMacRelaunchesSoTheToolkitLoadsBeforeAppKit() {
        assertEquals(AfterDownload.Relaunch, afterDownloadAction(isMac = true, canRelaunch = true))
    }

    @Test
    fun macWithoutAReliableRelaunchBootsInProcess() {
        assertEquals(AfterDownload.BootInProcess, afterDownloadAction(isMac = true, canRelaunch = false))
    }

    @Test
    fun otherPlatformsBootInProcess() {
        assertEquals(AfterDownload.BootInProcess, afterDownloadAction(isMac = false, canRelaunch = true))
        assertEquals(AfterDownload.BootInProcess, afterDownloadAction(isMac = false, canRelaunch = false))
    }
}
