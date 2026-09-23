package ai.rever.boss.kernel

/**
 * Merge orchestrator tuning into the process's own JVM args. A tuned heap flag replaces its
 * original counterpart, but only ever upward: RESTART_TUNED fires on OutOfMemoryError, and
 * swapping a larger existing heap for a smaller tuned one would deepen the crash loop it is
 * meant to break. Genuinely new flags are appended; every other original arg (GC flags,
 * --add-opens, ...) survives unchanged.
 */
internal fun mergeTunedJvmArgs(
    original: List<String>,
    tuned: List<String>,
): List<String> {
    val result = original.toMutableList()
    for (arg in tuned) {
        val key = heapFlagKey(arg)
        val idx = if (key != null) result.indexOfFirst { heapFlagKey(it) == key } else -1
        if (key != null && idx >= 0) {
            if (heapMegabytes(result[idx]) < heapMegabytes(arg)) result[idx] = arg
        } else if (arg !in result) {
            result += arg
        }
    }
    return result
}

/** The flag family of a heap arg (`-Xmx` / `-Xms`), or null for anything else. */
private fun heapFlagKey(arg: String): String? =
    when {
        arg.startsWith("-Xmx") -> "-Xmx"
        arg.startsWith("-Xms") -> "-Xms"
        else -> null
    }

/** Heap size of an `-Xmx`/`-Xms` arg in megabytes; an unparsable value counts as zero. */
private fun heapMegabytes(arg: String): Long {
    val raw = arg.removePrefix("-Xmx").removePrefix("-Xms")
    val unit = raw.lastOrNull()?.lowercaseChar()
    val number = raw.dropLast(1).toLongOrNull() ?: raw.toLongOrNull() ?: return 0
    return when (unit) {
        'g' -> number * 1024
        'm' -> number
        'k' -> number / 1024
        else -> number / (1024 * 1024)
    }
}
