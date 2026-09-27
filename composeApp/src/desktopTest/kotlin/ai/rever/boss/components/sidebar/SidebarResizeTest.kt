package ai.rever.boss.components.sidebar

import ai.rever.boss.window.TabBarVerticalWidthRange
import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarResizeTest {
    private val settings = WindowAppearanceSettings(tabBarVerticalWidth = 240f, tabBarCollapsed = false)

    @Test
    fun `release below minimum collapses and preserves expanded width`() {
        val result = sidebarResizeResult(settings, TabBarVerticalWidthRange.start - 1f)
        assertTrue(result.tabBarCollapsed)
        assertEquals(240f, result.tabBarVerticalWidth)
        assertEquals(settings, result.copy(tabBarCollapsed = false))
    }

    @Test
    fun `release at minimum keeps the sidebar expanded`() {
        val result = sidebarResizeResult(settings, TabBarVerticalWidthRange.start)
        assertFalse(result.tabBarCollapsed)
        assertEquals(TabBarVerticalWidthRange.start, result.tabBarVerticalWidth)
    }

    @Test
    fun `resizing a revealed collapsed sidebar persists expanded state`() {
        val collapsed = settings.copy(tabBarCollapsed = true)
        for (width in listOf(TabBarVerticalWidthRange.start, 300f)) {
            val result = sidebarResizeResult(collapsed, width)
            assertEquals(settings.copy(tabBarVerticalWidth = width), result)
        }
    }

    @Test
    fun `drag can preview below minimum and reverse before release`() {
        assertEquals(80f, sidebarResizePreview(80f))
        assertEquals(44f, sidebarResizePreview(-50f))
        val result = sidebarResizeResult(settings, 180f)
        assertFalse(result.tabBarCollapsed)
        assertEquals(180f, result.tabBarVerticalWidth)
    }

    @Test
    fun `expanding respects maximum width`() {
        assertEquals(TabBarVerticalWidthRange.endInclusive, sidebarResizePreview(1000f))
        assertEquals(TabBarVerticalWidthRange.endInclusive, sidebarResizeResult(settings, 1000f).tabBarVerticalWidth)
    }
}
