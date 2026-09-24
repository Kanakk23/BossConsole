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
                    "It has not been opened. Confirm only if you recognise it:\n\n${visibleUrlForApproval(request.url)}",
            confirmText = "Open link",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}

/** Render invisible and direction-changing characters as visible escapes in the approval prompt. */
internal fun visibleUrlForApproval(url: String): String =
    buildString {
        url.forEach { char ->
            val code = char.code
            if (code < 0x20 || code in 0x7f..0x9f || code == 0x034f || code == 0x061c ||
                code in 0x200b..0x200f || code in 0x202a..0x202e || code in 0x2060..0x206f ||
                code == 0xfeff
            ) {
                append("\\u")
                append(code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
