package ai.rever.boss.window

import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.theme.AppThemeSettingsManager
import ai.rever.boss.theme.WindowGlass
import ai.rever.boss.theme.isGlassTheme
import ai.rever.boss.utils.SystemUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Timer

/** Window owns native resources. The timer also catches system accessibility and frame changes. */
@Composable
internal fun rememberNativeWindowGlass(
    window: ComposeWindow,
    fullscreen: Boolean,
): WindowGlass {
    val settings by AppThemeSettingsManager.settings.collectAsState()
    val theme = BossThemeController.current
    var installed by remember(window) { mutableStateOf(false) }
    val mac = SystemUtils.isMacOS
    val enabled = mac && isGlassTheme(theme.id) && settings.glassCoverage in setOf("sidebar", "window")
    val currentFullscreen by rememberUpdatedState(fullscreen)
    DisposableEffect(window, enabled, theme.isLight, settings.glassStyle) {
        var controller: MacWindowGlass? = null

        fun update() {
            if (mac && window.isShowing && window.windowHandle != 0L) {
                if (controller == null) controller = MacWindowGlass(window.windowHandle) { installed = it }
                controller?.update(
                    GlassRequest(
                        enabled,
                        androidx.compose.ui.unit
                            .IntSize(window.width.coerceAtLeast(1), window.height.coerceAtLeast(1)),
                        !theme.isLight,
                        settings.glassStyle == "clear",
                        currentFullscreen,
                    ),
                )
            }
        }
        val listener =
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = update()

                override fun componentShown(e: ComponentEvent) = update()
            }
        val timer = Timer(2000) { update() }
        if (mac) {
            window.addComponentListener(listener)
            timer.start()
            update()
        }
        onDispose {
            timer.stop()
            window.removeComponentListener(listener)
            controller?.close()
            installed = false
        }
    }
    return WindowGlass(installed && enabled, settings.glassCoverage, settings.glassTint, settings.glassOpacity)
}
