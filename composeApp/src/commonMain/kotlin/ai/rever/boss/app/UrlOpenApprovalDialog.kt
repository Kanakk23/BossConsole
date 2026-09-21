package ai.rever.boss.app

import ai.rever.boss.components.dialogs.ConfirmationDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Each distinct request gets a fresh arming interval, including identical URLs. */
@Composable
internal fun UrlOpenApprovalDialog(
    request: PendingUrlOpen,
    pendingCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    key(request) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            armed = true
        }
        ConfirmationDialog(
            title = "Open this link? ($pendingCount pending)",
            message =
                "BOSS was asked from outside the app to open a link in a new browser tab. " +
                    "It has not been opened. Confirm only if you recognise it:\n\n${request.url}",
            confirmText = "Open link",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}
