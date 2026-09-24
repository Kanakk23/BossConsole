package ai.rever.boss.kernel

/**
 * Merge orchestrator tuning into the process's own JVM args. A tuned heap flag replaces its
 * original counterpart, but only ever upward: RESTART_TUNED fires on OutOfMemoryError, and
 * swapping a larger existing heap for a smaller tuned one would deepen the crash loop it is
 * meant to break. A tuned -Xmx with no explicit -Xmx to raise is dropped: the JVM's default
 * max heap (a share of RAM, or whatever -XX:MaxRAMPercentage / -XX:MaxHeapSize set) is
 * usually larger than the tuned value, so appending it could shrink the heap. Other new
 * flags are appended; every other original arg (GC flags, --add-opens, ...) survives
 * unchanged.
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
            if (isRaise(result[idx], arg)) result[idx] = arg
        } else if (key == "-Xmx") {
            continue
        } else if (arg !in result) {
            result += arg
        }
    }
    // A tuned -Xms may not end up above the final -Xmx: the JVM refuses to launch with
    // Xms > Xmx, which would turn the remedy into the crash loop it is meant to break.
    // Raise -Xmx to match - never lower either value; upward-only applies to both flags.
    val xmsIdx = result.indexOfFirst { heapFlagKey(it) == "-Xms" }
    val xmxIdx = result.indexOfFirst { heapFlagKey(it) == "-Xmx" }
    if (xmsIdx >= 0 && xmxIdx >= 0 && isRaise(result[xmxIdx], result[xmsIdx])) {
        result[xmxIdx] = "-Xmx" + result[xmsIdx].removePrefix("-Xms")
    }
    return result
}

/**
 * True when [candidate] is a strictly larger heap than [current]. An unparsable value on
 * either side is never a raise: an operator's value we can't read is left alone.
 */
private fun isRaise(
    current: String,
    candidate: String,
): Boolean {
    val from = heapMegabytes(current)
    val to = heapMegabytes(candidate)
    return from != null && to != null && from < to
}

/** The flag family of a heap arg (`-Xmx` / `-Xms`), or null for anything else. */
private fun heapFlagKey(arg: String): String? =
    when {
        arg.startsWith("-Xmx") -> "-Xmx"
        arg.startsWith("-Xms") -> "-Xms"
        else -> null
    }

/**
 * Heap size of an `-Xmx`/`-Xms` arg in megabytes, or null when the value is unparsable.
 *
 * JVM syntax treats a unitless value as bytes, so the unit letter is only consumed after the
 * all-digits case is handled: peeling the last character first would silently drop the last
 * digit of a byte value (`-Xmx8589934592` read as ~819 MB instead of 8192) and the
 * upward-only merge could then swap a larger existing heap for a smaller tuned one. An
 * unrecognised suffix is unparsable rather than "bytes": reading junk as bytes would shrink a
 * heap the operator set deliberately.
 */
private fun heapMegabytes(arg: String): Long? {
    val raw = arg.removePrefix("-Xmx").removePrefix("-Xms")
    val bytes = raw.toLongOrNull() // all-digits: unitless means bytes
    val number = raw.dropLast(1).toLongOrNull()
    val unit = raw.lastOrNull()?.lowercaseChar()
    return when {
        bytes != null -> bytes / (1024 * 1024)
        number == null -> null
        unit == 'g' -> number * 1024
        unit == 'm' -> number
        unit == 'k' -> number / 1024
        else -> null
    }
}
