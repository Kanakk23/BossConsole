package ai.rever.boss.app

import androidx.compose.runtime.mutableStateListOf

/** Window-owned FIFO, accessed only on the UI thread. Closing the window drops its requests. */
internal class TerminalCommandApprovalQueue {
    private val requests = mutableStateListOf<PendingTerminalCommand>()

    val current: PendingTerminalCommand?
        get() = requests.firstOrNull()

    fun enqueue(request: PendingTerminalCommand) {
        requests.add(request)
    }

    /** Only the request actually shown can be consumed, once, even for identical commands. */
    fun consume(request: PendingTerminalCommand): Boolean {
        if (current !== request) return false
        requests.removeAt(0)
        return true
    }
}
