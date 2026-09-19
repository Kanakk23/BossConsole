package ai.rever.boss.mcp

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpTelemetryServiceTest {

    @BeforeTest
    fun setup() {
        McpTelemetryService.reset()
    }

    @Test
    fun `recording invocations accumulates metrics correctly`() {
        McpTelemetryService.recordInvocation("git_status", durationMs = 150L, isError = false)
        McpTelemetryService.recordInvocation("git_status", durationMs = 250L, isError = false)
        McpTelemetryService.recordInvocation("git_status", durationMs = 500L, isError = true)

        val metrics = McpTelemetryService.getMetrics("git_status")
        assertNotNull(metrics)
        assertEquals("git_status", metrics.toolName)
        assertEquals(3L, metrics.callCount)
        assertEquals(2L, metrics.successCount)
        assertEquals(1L, metrics.failureCount)
        assertEquals(0L, metrics.timeoutCount)
        assertEquals(900L, metrics.totalDurationMs)
        assertEquals(150L, metrics.minDurationMs)
        assertEquals(500L, metrics.maxDurationMs)
        assertEquals(300.0, metrics.avgDurationMs)
        assertTrue(metrics.successRate > 66.0 && metrics.successRate < 67.0)
    }

    @Test
    fun `recording timeouts tracks timeout count`() {
        McpTelemetryService.recordInvocation("docker_ps", durationMs = 1000L, isError = true, isTimeout = true)

        val metrics = McpTelemetryService.getMetrics("docker_ps")
        assertNotNull(metrics)
        assertEquals(1L, metrics.callCount)
        assertEquals(0L, metrics.successCount)
        assertEquals(1L, metrics.failureCount)
        assertEquals(1L, metrics.timeoutCount)
        assertEquals(0.0, metrics.successRate)
    }

    @Test
    fun `reset clears all telemetry`() {
        McpTelemetryService.recordInvocation("codebase_read", durationMs = 50L)
        assertTrue(McpTelemetryService.getAllMetrics().isNotEmpty())

        McpTelemetryService.reset()
        assertTrue(McpTelemetryService.getAllMetrics().isEmpty())
    }

    @Test
    fun `tool_stats provider returns mcp tool definition`() {
        val provider = McpTelemetryService.createToolStatsProvider()
        assertEquals("telemetry-service", provider.providerId)

        val tools = provider.tools()
        assertEquals(1, tools.size)
        assertEquals("tool_stats", tools.single().name)
    }
}
