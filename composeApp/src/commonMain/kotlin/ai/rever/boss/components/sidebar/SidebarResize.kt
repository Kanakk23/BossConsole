package ai.rever.boss.components.sidebar

import ai.rever.boss.components.window_panel.components.main_window_panels.clampBarWidth
import ai.rever.boss.window.TabBarVerticalWidthRange
import ai.rever.boss.window.WindowAppearanceSettings

/** Allow a narrow preview, then decide whether to collapse on release, like BossTerm. */
internal fun sidebarResizePreview(width: Float): Float = width.coerceIn(44f, TabBarVerticalWidthRange.endInclusive)

/** Collapse below the minimum, preserving the width restored by the sidebar toggle. */
internal fun sidebarResizeResult(
    settings: WindowAppearanceSettings,
    requestedWidth: Float,
): WindowAppearanceSettings =
    if (requestedWidth < TabBarVerticalWidthRange.start) {
        settings.copy(tabBarCollapsed = true)
    } else {
        settings.copy(tabBarVerticalWidth = clampBarWidth(requestedWidth), tabBarCollapsed = false)
    }
