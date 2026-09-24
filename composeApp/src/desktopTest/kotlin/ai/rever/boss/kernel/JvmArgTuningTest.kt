package ai.rever.boss.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parser half of [mergeTunedJvmArgs] is load-bearing: it is the only thing standing
 * between RESTART_TUNED and a merge that swaps a larger existing heap for a smaller tuned
 * one - the exact crash-loop deepening the upward-only rule exists to prevent.
 */
class JvmArgTuningTest {
    @Test
    fun `unitless byte values keep their last digit and are read as bytes`() {
        // 8589934592 bytes = 8192 MB; a tuned 4096m must NOT replace it.
        val merged = mergeTunedJvmArgs(listOf("-Xmx8589934592"), listOf("-Xmx4096m"))
        assertEquals(listOf("-Xmx8589934592"), merged)
    }

    @Test
    fun `a larger tuned heap still replaces a smaller unitless original`() {
        // 1073741824 bytes = 1024 MB; tuned 2048m wins upward.
        val merged = mergeTunedJvmArgs(listOf("-Xmx1073741824"), listOf("-Xmx2048m"))
        assertEquals(listOf("-Xmx2048m"), merged)
    }

    @Test
    fun `g m and k suffixes compare across units`() {
        assertEquals(listOf("-Xmx4096m"), mergeTunedJvmArgs(listOf("-Xmx2g"), listOf("-Xmx4096m")))
        assertEquals(listOf("-Xmx2g"), mergeTunedJvmArgs(listOf("-Xmx2g"), listOf("-Xmx1048576k")))
    }

    @Test
    fun `an unparsable original heap is left alone`() {
        // '-Xmx4t' is not valid JVM syntax. Its real size is unknown, so replacing it
        // could shrink it: the merge keeps the operator's value.
        val merged = mergeTunedJvmArgs(listOf("-Xmx4t"), listOf("-Xmx2048m"))
        assertEquals(listOf("-Xmx4t"), merged)
    }

    @Test
    fun `a tuned Xmx is not appended when the original sets no Xmx`() {
        // The JVM default max heap (or -XX:MaxRAMPercentage) is usually above 512m, so
        // appending the tuned value could shrink it.
        assertEquals(emptyList<String>(), mergeTunedJvmArgs(emptyList(), listOf("-Xmx512m")))
        val gcOnly = listOf("-XX:+UseG1GC", "-XX:MaxRAMPercentage=75")
        assertEquals(gcOnly, mergeTunedJvmArgs(gcOnly, listOf("-Xmx512m")))
    }

    @Test
    fun `a tuned Xms above the final Xmx raises Xmx instead of breaking the launch`() {
        val merged = mergeTunedJvmArgs(listOf("-Xmx2048m", "-Xms1024m"), listOf("-Xms6144m"))
        assertTrue("-Xmx6144m" in merged, "Xmx must be raised to match the tuned Xms: $merged")
        assertTrue("-Xms6144m" in merged)
        assertFalse("-Xmx2048m" in merged)
    }

    @Test
    fun `a tuned Xms below the final Xmx leaves Xmx alone`() {
        val merged = mergeTunedJvmArgs(listOf("-Xmx4096m"), listOf("-Xms1024m"))
        assertEquals(listOf("-Xmx4096m", "-Xms1024m"), merged)
    }

    @Test
    fun `non-heap args survive and duplicates are not appended`() {
        val merged =
            mergeTunedJvmArgs(
                listOf("-Xmx2048m", "--add-opens=java.base/java.nio=ALL-UNNAMED"),
                listOf("-XX:+UseG1GC", "--add-opens=java.base/java.nio=ALL-UNNAMED"),
            )
        assertEquals(
            listOf("-Xmx2048m", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-XX:+UseG1GC"),
            merged,
        )
    }
}
