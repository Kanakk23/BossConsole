package ai.rever.boss.cli

import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import java.net.URLEncoder

/**
 * Main BOSS CLI command.
 *
 * Usage:
 *   boss <url>                      # Opens URL in browser
 *   boss workspace <config>         # Loads workspace
 *   boss file <path>                # Opens file in editor
 *   boss folder <path>              # Opens folder in codebase
 *   boss terminal                   # Opens terminal
 *   boss terminal -c <command>      # Opens terminal with command
 *
 * These commands are only ever built from this process's own `argv` — `main()`
 * hands the arguments the operator passed to the BOSS executable to
 * [createBossCLI] — so every link they produce carries
 * [DeepLinkOrigin.OPERATOR_CLI]. That is the one origin the terminal handler
 * runs a command for without prompting; see [DeepLinkOrigin].
 */
class BossCommand : NoOpCliktCommand(name = "boss") {
    override fun help(context: Context) = "BOSS Console - Business Operating System + Simulation"
}

/**
 * Opens URL in Fluck browser tab.
 * Usage: boss url https://example.com
 */
class BossUrlCommand : CliktCommand(name = "url") {
    override fun help(context: Context) = "Opens a URL in Fluck browser"

    val url by argument(help = "URL to open")

    override fun run() {
        // Convert to deep link
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val deepLink = "boss://url?url=$encodedUrl"
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Loads workspace configuration.
 * Usage: boss workspace myworkspace.json
 */
class BossWorkspaceCommand : CliktCommand(name = "workspace") {
    override fun help(context: Context) = "Loads a workspace configuration"

    val configPath by argument(help = "Path to workspace config file")

    override fun run() {
        // Convert to deep link
        val encodedPath = URLEncoder.encode(configPath, "UTF-8")
        val deepLink = "boss://workspace?path=$encodedPath"
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Opens file in editor tab.
 * Usage: boss file /path/to/file.kt
 */
class BossFileCommand : CliktCommand(name = "file") {
    override fun help(context: Context) = "Opens a file in the editor"

    val filePath by argument(help = "Path to file")

    override fun run() {
        // Convert to deep link
        val encodedPath = URLEncoder.encode(filePath, "UTF-8")
        val deepLink = "boss://file?path=$encodedPath"
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Opens folder in codebase plugin.
 * Usage: boss folder /path/to/project
 */
class BossFolderCommand : CliktCommand(name = "folder") {
    override fun help(context: Context) = "Opens a folder in the codebase plugin"

    val folderPath by argument(help = "Path to folder")

    override fun run() {
        // Convert to deep link
        val encodedPath = URLEncoder.encode(folderPath, "UTF-8")
        val deepLink = "boss://folder?path=$encodedPath"
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Opens terminal tab, optionally with command.
 * Usage:
 *   boss terminal
 *   boss terminal -c "ls -la"
 */
class BossTerminalCommand : CliktCommand(name = "terminal") {
    override fun help(context: Context) = "Opens a terminal tab"

    val command by option("-c", "--command", help = "Command to run in terminal")

    override fun run() {
        // Convert to deep link
        val deepLink =
            if (command != null) {
                val encodedCommand = URLEncoder.encode(command, "UTF-8")
                "boss://terminal?command=$encodedCommand"
            } else {
                "boss://terminal"
            }
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Invokes or inspects an MCP tool over deep link protocol.
 * Usage:
 *   boss mcp --tool codebase_read --args '{"path":"README.md"}'
 */
class BossMcpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context) = "Invokes an MCP tool in BOSS"

    val tool by option("-t", "--tool", help = "MCP tool name to invoke")
    val args by option("-a", "--args", help = "JSON arguments for the MCP tool")

    override fun run() {
        val toolName = tool
        val argsValue = args
        val deepLink =
            if (toolName != null) {
                val encodedTool = URLEncoder.encode(toolName, "UTF-8")
                val encodedArgs = argsValue?.let { URLEncoder.encode(it, "UTF-8") }
                if (encodedArgs != null) {
                    "boss://mcp?tool=$encodedTool&args=$encodedArgs"
                } else {
                    "boss://mcp?tool=$encodedTool"
                }
            } else {
                "boss://mcp"
            }
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Configures Clikt command structure.
 */
fun createBossCLI(): BossCommand =
    BossCommand().subcommands(
        BossUrlCommand(),
        BossWorkspaceCommand(),
        BossFileCommand(),
        BossFolderCommand(),
        BossTerminalCommand(),
        BossMcpCommand(),
    )
