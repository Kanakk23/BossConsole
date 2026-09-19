package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
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

    /**
     * Spawns a short-lived process that exits immediately, returning a PID that is
     * guaranteed to be terminated and no longer running.
     */
    private fun getTerminatedPid(): Long {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val command = if (isWindows) listOf("cmd", "/c", "exit 0") else listOf("true")
        val process = ProcessBuilder(command).start()
        process.waitFor()
        val pid = process.pid()
        var attempts = 0
        while (isProcessAlive(pid) && attempts < 50) {
            Thread.sleep(10)
            attempts++
        }
        return pid
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

        val deadPid = getTerminatedPid()
        assertFalse(isProcessAlive(deadPid), "Terminated process PID should not be alive")
    }

    @Test
    fun `isAnotherInstanceRunning immediately returns false for dead pid without network ping`() {
        val deadPid = getTerminatedPid()
        ServerSocket(0).use { serverSocket ->
            serverSocket.soTimeout = 50
            val staleDescriptor =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.TCP,
                    endpoint = serverSocket.localPort.toString(),
                    token = "c".repeat(TOKEN_HEX_LENGTH),
                    pid = deadPid,
                )

            SingleInstanceFiles.prepare()
            SingleInstanceFiles.write(staleDescriptor)

            // Must return false immediately because PID is known dead
            val running = SingleInstanceManager.isAnotherInstanceRunning()
            assertFalse(running, "Should not detect running instance when PID is dead")

            // Verify that no socket connection or ping was attempted against the endpoint
            assertThrows<SocketTimeoutException> {
                serverSocket.accept()
            }
        }
    }

    @Test
    fun `acquireLock immediately reclaims stale descriptor with dead pid`() {
        val deadPid = getTerminatedPid()
        ServerSocket(0).use { serverSocket ->
            serverSocket.soTimeout = 50
            val staleDescriptor =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.TCP,
                    endpoint = serverSocket.localPort.toString(),
                    token = "d".repeat(TOKEN_HEX_LENGTH),
                    pid = deadPid,
                )

            SingleInstanceFiles.prepare()
            SingleInstanceFiles.write(staleDescriptor)

            // Attempting to acquire lock should instantly succeed by reclaiming the stale descriptor
            val acquired = SingleInstanceManager.acquireLock()
            assertTrue(acquired, "acquireLock must succeed by reclaiming stale descriptor from dead process")

            // Verify that no socket connection or ping was attempted before reclaiming
            assertThrows<SocketTimeoutException> {
                serverSocket.accept()
            }

            val published = SingleInstanceFiles.read()
            assertNotNull(published)
            assertEquals(ProcessHandle.current().pid(), published.pid)
        }
    }
}
