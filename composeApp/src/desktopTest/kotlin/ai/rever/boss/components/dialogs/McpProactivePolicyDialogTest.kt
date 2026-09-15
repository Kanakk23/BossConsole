package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.plugin.ui.BossBlueprintColorScheme
import ai.rever.boss.plugin.ui.BossBlueprintLightColorScheme
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.LocalBossColors
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpProactivePolicyDialogTest {
    @get:Rule val rule = createComposeRule()
    private val previousRenderer = BossOverlayHost.modalRenderer
    private val previousHeavyweight = BossOverlayHost.useHeavyweightOverlays

    @Before fun setup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.modalRenderer = { _, _, content -> content() }
    }

    @After fun cleanup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.modalRenderer = previousRenderer
        BossOverlayHost.useHeavyweightOverlays = previousHeavyweight
    }

    private var captureTheme = "dark"

    private fun show(
        light: Boolean = false,
        windowSize: IntSize = IntSize(700, 360),
        content: @Composable () -> Unit,
    ) {
        captureTheme = if (light) "light" else "dark"
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides if (light) BossBlueprintLightColorScheme else BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = windowSize
                    },
            ) {
                val width = (if (windowSize.width > 0) windowSize.width else 700).dp
                Box(Modifier.size(width, 360.dp).clipToBounds()) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(250)
    }

    private var captureIndex = 0

    private fun closeIsInsideWindow() {
        if (System.getenv("BOSS_REVIEW_CAPTURE") == "1") captureLayout()
        rule.onNodeWithText("Close").assertIsDisplayed()
        val bounds = rule.onNodeWithText("Close").getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= 0.dp && bounds.bottom <= 360.dp, "Close bounds: $bounds")
    }

    private fun captureLayout() {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val name = "${javaClass.simpleName}-$captureTheme-${++captureIndex}.png"
        val output = File("build/reports/mcp-review", name)
        output.parentFile.mkdirs()
        javax.imageio.ImageIO.write(image, "png", output)
    }

    @Test fun `short window keeps close visible with saved and proactive rows`() {
        var closed = false
        show {
            McpPolicyManagerDialog(
                rules = (1..30).associate { "saved-$it" to McpPolicyAction.DENY },
                availableTools = listOf(McpToolIdentity("tool", "provider".repeat(80), 0)),
                onRevoke = { true },
                onSetPolicy = { _, _ -> McpProactivePolicyOutcome.Saved },
                onRefreshCandidates = {},
                onDismiss = { closed = true },
            )
        }
        closeIsInsideWindow()
        rule.onNodeWithText("Allow", substring = false).performScrollTo().assertIsDisplayed()
        closeIsInsideWindow()
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertTrue(closed) }
    }

    @Test fun `replacing a candidate invalidates the armed confirmation in light theme`() {
        val candidate = mutableStateOf(McpToolIdentity("tool", "provider", 0))
        var writes = 0
        show(light = true) {
            McpPolicyManagerDialog(
                emptyMap(),
                listOf(candidate.value),
                { true },
                { _, _ ->
                    writes++
                    McpProactivePolicyOutcome.Saved
                },
                {},
                {},
            )
        }
        rule.onNodeWithText("Allow", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Confirm allow?").assertExists()
        rule.runOnIdle { candidate.value = candidate.value.copy(expectedRevocation = 1) }
        rule.onNodeWithText("Confirm allow?").assertDoesNotExist()
        rule.onNodeWithText("Allow", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Confirm allow?").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, writes) }
    }

    @Test fun `refused write refreshes candidates and reports refusal distinctly from disk failure`() {
        var refreshes = 0
        show {
            McpPolicyManagerDialog(
                emptyMap(),
                listOf(McpToolIdentity("tool", "provider", 0)),
                { true },
                { _, _ -> McpProactivePolicyOutcome.Refused },
                { refreshes++ },
                {},
            )
        }
        rule.onNodeWithText("Deny", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Policy changed.", substring = true).assertExists()
        rule.runOnIdle { assertEquals(1, refreshes) }
        assertTrue(McpProactivePolicyOutcome.Failed("disk").proactivePolicyMessage()!!.contains("storage"))
    }

    @Test fun `unreadable policy explains recovery inside the modal`() {
        show {
            McpPolicyManagerDialog(
                emptyMap(),
                listOf(McpToolIdentity("tool", "provider", 0)),
                { true },
                { _, _ -> McpProactivePolicyOutcome.PolicyUnreadable },
                {},
                {},
            )
        }
        rule.onNodeWithText("Deny", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Policy file unreadable:", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(McpProactivePolicyOutcome.Denied.proactivePolicyMessage()!!.contains("already denies"))
    }

    @Test fun `unknown window size retains a usable dialog`() {
        show(windowSize = IntSize.Zero) {
            McpPolicyManagerDialog(
                emptyMap(),
                emptyList(),
                { true },
                { _, _ -> McpProactivePolicyOutcome.Saved },
                {},
                {},
            )
        }
        closeIsInsideWindow()
    }

    @Test fun `narrow window keeps close horizontally inside the viewport`() {
        show(windowSize = IntSize(360, 360)) {
            McpPolicyManagerDialog(
                emptyMap(),
                emptyList(),
                { true },
                { _, _ -> McpProactivePolicyOutcome.Saved },
                {},
                {},
            )
        }
        closeIsInsideWindow()
        assertTrue(rule.onNodeWithText("Close").getUnclippedBoundsInRoot().right <= 360.dp)
    }
}
