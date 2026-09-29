package ai.rever.boss.sharing

import ai.rever.boss.plugin.ui.BossThemeController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reserves real layout space above browser surfaces, keeping Stop visible during capture. */
@Composable
internal fun AppSharingChrome(
    windowId: String,
    content: @Composable () -> Unit,
) {
    val state by AppSharingService.state.collectAsState()
    val colors = BossThemeController.current.colors
    Column(Modifier.fillMaxSize()) {
        if (windowId in state.activeWindowIds || state.statusWindowId == windowId) {
            Row(
                Modifier.fillMaxWidth().background(colors.panel).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.status,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.controller) {
                    TextButton(
                        onClick = { AppSharingService.takeBackControl() },
                    ) { Text("Take back control", color = colors.signal) }
                }
                if (windowId in state.activeWindowIds) {
                    TextButton(onClick = { AppSharingService.stop() }) { Text("Stop sharing", color = colors.signal) }
                } else {
                    TextButton(onClick = { AppSharingService.dismissStatus() }) {
                        Text("Dismiss", color = colors.signal)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
