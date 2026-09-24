package ai.rever.boss.services

/**
 * Sliding-window bound on untrusted URL requests that require operator approval.
 *
 * External `boss://url` requests pass through [URLHandlerService.handleURL].
 * Without a bound, a loop of requests can fill the approval queue.
 * [tryAcquire] admits at most [MAX_OPENS] opens per [WINDOW_MS]; the rest are
 * dropped with a log line by the caller.
 *
 * Thread-safe: [URLHandlerService.handleURL] is called off the UI thread by
 * deep-link and CLI paths.
 */
internal class UrlOpenRateLimiter(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val opens = ArrayDeque<Long>()

    /** Records an open and returns true, or returns false once [MAX_OPENS] opens sit inside the window. */
    fun tryAcquire(): Boolean =
        synchronized(lock) {
            val now = nowMs()
            while (opens.isNotEmpty() && now - opens.first() >= WINDOW_MS) {
                opens.removeFirst()
            }
            if (opens.size >= MAX_OPENS) {
                false
            } else {
                opens.addLast(now)
                true
            }
        }

    companion object {
        const val MAX_OPENS = 5
        const val WINDOW_MS = 5_000L
    }
}
