package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies PID tracking and instant recovery from stale single-instance descriptors left
 * by crashed or terminated processes.
 */
class SingleInstanceStalePidTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        SingleInstanceManager.runtimeDirOverride = File(tempDir.toFile(), "run")
        SingleInstanceManager.llmTokenProviderOverride = null
    }

    @AfterEach
    fun tearDown() {
        SingleInstanceManager.release()
        SingleInstanceManager.llmTokenProviderOverride = null
        SingleInstanceManager.runtimeDirOverride = null
    }

    @Test
    fun `descriptor with pid round trips through encoding and parsing`() {
        val descriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = "56789",
                token = "a".repeat(TOKEN_HEX_LENGTH),
                pid = 424242L,
            )

        val encoded = descriptor.encode()
        assertTrue(encoded.contains("pid=424242"))

        val parsed = parseInstanceDescriptor(encoded)
        assertNotNull(parsed)
        assertEquals(descriptor, parsed)
        assertEquals(424242L, parsed.pid)
    }

    @Test
    fun `descriptor without pid still parses cleanly with null pid`() {
        val token = "b".repeat(TOKEN_HEX_LENGTH)
        val legacyText = "version=1\ntransport=TCP\nendpoint=56789\ntoken=$token"

        val parsed = parseInstanceDescriptor(legacyText)
        assertNotNull(parsed)
        assertNull(parsed.pid)
        assertEquals(SingleInstanceTransport.TCP, parsed.transport)
    }

    @Test
    fun `isProcessAlive correctly identifies current process and dead pids`() {
        val currentPid = ProcessHandle.current().pid()
        assertTrue(isProcessAlive(currentPid), "Current JVM process must be alive")

        // An impossibly high PID should report not alive
        val deadPid = 99_999_999L
        assertFalse(isProcessAlive(deadPid), "Non-existent PID should not be alive")
    }

    @Test
    fun `isAnotherInstanceRunning immediately returns false for dead pid without network ping`() {
        val deadPid = 99_999_999L
        val staleDescriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = "59999",
                token = "c".repeat(TOKEN_HEX_LENGTH),
                pid = deadPid,
            )

        SingleInstanceFiles.prepare()
        SingleInstanceFiles.write(staleDescriptor)

        // Must return false immediately because PID is known dead
        val running = SingleInstanceManager.isAnotherInstanceRunning()
        assertFalse(running, "Should not detect running instance when PID is dead")
    }

    @Test
    fun `acquireLock immediately reclaims stale descriptor with dead pid`() {
        val deadPid = 99_999_999L
        val staleDescriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = "59999",
                token = "d".repeat(TOKEN_HEX_LENGTH),
                pid = deadPid,
            )

        SingleInstanceFiles.prepare()
        SingleInstanceFiles.write(staleDescriptor)

        // Attempting to acquire lock should instantly succeed by reclaiming the stale descriptor
        val acquired = SingleInstanceManager.acquireLock()
        assertTrue(acquired, "acquireLock must succeed by reclaiming stale descriptor from dead process")

        val published = SingleInstanceFiles.read()
        assertNotNull(published)
        assertEquals(ProcessHandle.current().pid(), published.pid)
    }
}
