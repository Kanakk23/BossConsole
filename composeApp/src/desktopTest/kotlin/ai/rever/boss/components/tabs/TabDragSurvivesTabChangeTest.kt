package ai.rever.boss.components.tabs

import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.window_panel.components.main_window_panels.TabFaviconChip
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins that a tab drag survives the tab changing underneath it.
 *
 * The stuck-ghost bug: the drag gesture was keyed on the TabInfo, the panel id and the tab's index,
 * so any of the three changing restarted its `pointerInput`. A TabInfo is a data class carrying the
 * title, so a terminal writing a new title or a page finishing its load hands the tab list a fresh
 * instance mid-drag - and the index moves whenever anything else in the panel opens or closes. A
 * restart cancels the gesture's coroutine WITHOUT calling onDragEnd or onDragCancel, so the release
 * that followed went nowhere: `draggingTab` stayed set and the ghost window kept tracking the
 * cursor for the rest of the session, with no gesture left alive to put it down.
 *
 * A composition test rather than a unit one because the bug is entirely in the modifier's keys -
 * every piece of `TabDraggableComponent` it exercises was already correct on its own.
 */
@OptIn(ExperimentalTestApi::class)
class TabDragSurvivesTabChangeTest {
    @Test
    fun `a title change mid-drag does not strand the drag`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var title by mutableStateOf("Running")

            setContent {
                Row(modifier = Modifier.testTag(CHIP_TAG)) {
                    TabFaviconChip(
                        tab = DragTestTab("tab-1", title = title),
                        isActive = true,
                        onClick = {},
                        tabDragComponent = component,
                        panelId = "panel-1",
                        tabIndex = 0,
                    )
                }
            }

            val chip = onNodeWithTag(CHIP_TAG)
            chip.performTouchInput {
                down(center)
                moveBy(Offset(160f, 0f))
            }
            assertNotNull(component.draggingTab, "the drag should be under way")

            // What the terminal does every time its command changes: a brand-new TabInfo.
            title = "Review oldest pull request"
            waitForIdle()
            assertNotNull(component.draggingTab, "a new title must not end the drag")

            chip.performTouchInput { up() }
            assertNull(component.draggingTab, "the release must put the ghost down")
        }

    @Test
    fun `an index change mid-drag does not strand the drag`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var index by mutableStateOf(2)

            setContent {
                Row(modifier = Modifier.testTag(CHIP_TAG)) {
                    TabFaviconChip(
                        tab = DragTestTab("tab-1"),
                        isActive = true,
                        onClick = {},
                        tabDragComponent = component,
                        panelId = "panel-1",
                        tabIndex = index,
                    )
                }
            }

            val chip = onNodeWithTag(CHIP_TAG)
            chip.performTouchInput {
                down(center)
                moveBy(Offset(160f, 0f))
            }
            assertEquals(2, component.draggingTab?.sourceIndex, "the drag starts from where the tab was")

            // A tab ahead of this one closed while the drag was in the air.
            index = 1
            waitForIdle()
            assertNotNull(component.draggingTab, "a reindex must not end the drag")

            chip.performTouchInput { up() }
            assertNull(component.draggingTab, "the release must put the ghost down")
        }
}

private const val CHIP_TAG = "drag-chip"

private data class DragTestTab(
    override val id: String,
    override val typeId: TabTypeId = TabTypeId("drag-test", "test.plugin"),
    override val title: String = "Chip Test Tab",
) : TabInfo {
    override val icon get() = Icons.Outlined.Language
}
