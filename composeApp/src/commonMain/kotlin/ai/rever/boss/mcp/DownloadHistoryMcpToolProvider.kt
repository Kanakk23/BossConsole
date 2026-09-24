package ai.rever.boss.mcp

import ai.rever.boss.downloads.DownloadHistoryManager
import ai.rever.boss.downloads.DownloadRecord
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Host MCP tool provider exposing the persistent download history
 * ([DownloadHistoryManager]) to AI agents and automation clients, so an agent that triggered a
 * download can confirm what landed and where.
 *
 * `downloads_history_list` declares `readOnly = true` (left at ALLOW by the mutating gate);
 * `downloads_history_clear` declares `readOnly = false` (classified mutating and routed through
 * the usual ASK approval), matching the posture of the other host providers.
 */
object DownloadHistoryMcpToolProvider : McpToolProvider {
    private val logger = BossLogger.forComponent("DownloadHistoryMcpToolProvider")
    override val providerId: String = "boss-downloads"

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createListTool(),
            createClearTool(),
        )

    private fun createListTool(): McpToolDefinition =
        McpToolDefinition(
            name = "downloads_history_list",
            description = "List completed downloads recorded across sessions (newest first).",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {}
                }
                """.trimIndent(),
            handler = McpToolHandler { handleList() },
            readOnly = true,
        )

    private fun createClearTool(): McpToolDefinition =
        McpToolDefinition(
            name = "downloads_history_clear",
            description = "Clear the persistent download history.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {}
                }
                """.trimIndent(),
            handler = McpToolHandler { handleClear() },
            readOnly = false,
        )

    private fun handleList(): McpToolResult {
        if (DownloadHistoryManager.loadFailed) {
            return McpToolResult("Download history could not be read; clear it before listing", isError = true)
        }
        val response =
            buildJsonObject {
                put("success", true)
                put(
                    "downloads",
                    buildJsonArray { DownloadHistoryManager.downloads.value.forEach { add(recordJson(it)) } },
                )
            }
        return McpToolResult(response.toString())
    }

    @Suppress("TooGenericExceptionCaught") // persistence can fail through several filesystem exception types
    private suspend fun handleClear(): McpToolResult {
        val removed =
            try {
                DownloadHistoryManager.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Could not clear download history", error = e)
                return McpToolResult("Could not persist cleared download history", isError = true)
            }
        return McpToolResult(
            buildJsonObject {
                put("success", true)
                put("removed", removed)
            }.toString(),
        )
    }

    private fun recordJson(record: DownloadRecord) =
        buildJsonObject {
            put("id", record.id)
            put("url", record.url)
            put("fileName", record.fileName)
            put("filePath", record.filePath)
            record.sizeBytes?.let { put("sizeBytes", it) }
            put("completedAt", record.completedAt)
        }
}
