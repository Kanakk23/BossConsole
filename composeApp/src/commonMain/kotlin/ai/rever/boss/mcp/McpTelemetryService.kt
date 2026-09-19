package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Performance & Telemetry statistics for a single MCP tool.
 */
@Serializable
data class McpToolMetricsDto(
    val toolName: String,
    val callCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val timeoutCount: Long,
    val avgDurationMs: Double,
    val successRate: Double,
)

data class McpToolMetrics(
    val toolName: String,
    val callCount: Long = 0L,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val timeoutCount: Long = 0L,
    val totalDurationMs: Long = 0L,
    val minDurationMs: Long = Long.MAX_VALUE,
    val maxDurationMs: Long = 0L,
    val lastCallTimestamp: Long = 0L,
) {
    val avgDurationMs: Double
        get() = if (callCount > 0) totalDurationMs.toDouble() / callCount else 0.0

    val successRate: Double
        get() = if (callCount > 0) (successCount.toDouble() / callCount) * 100.0 else 100.0

    fun toDto(): McpToolMetricsDto = McpToolMetricsDto(
        toolName = toolName,
        callCount = callCount,
        successCount = successCount,
        failureCount = failureCount,
        timeoutCount = timeoutCount,
        avgDurationMs = avgDurationMs,
        successRate = successRate,
    )
}

/**
 * Service collecting per-tool metrics: call frequency, execution latency,
 * success/failure counts, and timeouts across all MCP tool invocations.
 */
object McpTelemetryService {
    private val metricsMap = ConcurrentHashMap<String, McpToolMetrics>()
    private val _telemetryFlow = MutableStateFlow<List<McpToolMetrics>>(emptyList())

    /**
     * StateFlow emitting real-time snapshots of all recorded MCP tool metrics.
     */
    val telemetryFlow: StateFlow<List<McpToolMetrics>> = _telemetryFlow.asStateFlow()

    /**
     * Records a tool invocation result.
     */
    fun recordInvocation(
        toolName: String,
        durationMs: Long,
        isError: Boolean = false,
        isTimeout: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        metricsMap.compute(toolName) { _, existing ->
            val current = existing ?: McpToolMetrics(toolName)
            current.copy(
                callCount = current.callCount + 1,
                successCount = current.successCount + if (!isError && !isTimeout) 1 else 0,
                failureCount = current.failureCount + if (isError) 1 else 0,
                timeoutCount = current.timeoutCount + if (isTimeout) 1 else 0,
                totalDurationMs = current.totalDurationMs + durationMs,
                minDurationMs = minOf(current.minDurationMs, durationMs),
                maxDurationMs = maxOf(current.maxDurationMs, durationMs),
                lastCallTimestamp = now,
            )
        }
        updateSnapshot()
    }

    /**
     * Retrieves recorded metrics for a specific tool name.
     */
    fun getMetrics(toolName: String): McpToolMetrics? = metricsMap[toolName]

    /**
     * Retrieves recorded metrics for all tools.
     */
    fun getAllMetrics(): List<McpToolMetrics> = metricsMap.values.toList()

    /**
     * Clears all collected telemetry metrics.
     */
    fun reset() {
        metricsMap.clear()
        updateSnapshot()
    }

    private fun updateSnapshot() {
        _telemetryFlow.value = metricsMap.values.sortedByDescending { it.callCount }
    }

    /**
     * Provider exposing the `mcp__boss__tool_stats` tool for agents to inspect tool reliability.
     */
    fun createToolStatsProvider(): McpToolProvider = object : McpToolProvider {
        override val providerId: String get() = "telemetry-service"

        override fun tools(): List<McpToolDefinition> = listOf(
            McpToolDefinition(
                name = "tool_stats",
                description = "Retrieves execution statistics (latency, call count, success rate) for all MCP tools.",
                handler = McpToolHandler {
                    val dtos = getAllMetrics().map { it.toDto() }
                    val jsonStr = Json.encodeToString(dtos)
                    McpToolResult(jsonStr)
                },
            )
        )
    }
}
