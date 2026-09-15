package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Lets an operator see and undo every persistent MCP tool policy rule saved from the approval
 * dialog's "Always Allow"/"Always Deny" actions ([McpApprovalDialog]) - the inspection and
 * revocation path this repo's own AGENTS.md names as reachable only by hand-editing
 * `~/.boss/mcp-tool-policy.json` and restarting, until this dialog existed.
 *
 * It also lets an operator set a rule *proactively*, for a tool nothing has ever asked about yet.
 * Before this, the only way a persistent rule came to exist was reactive: a plugin had to actually
 * invoke the tool and trigger the approval dialog, and only then could "Always Allow"/"Always
 * Deny" be clicked. An operator who already knows they never want a given tool run - `k8s_delete`,
 * say - had no way to say so ahead of time; they could only wait to be asked, or hand-edit the
 * policy file this dialog exists to make unnecessary. [availableTools] is every registered tool
 * that does not already have a rule in [rules], and [onSetPolicy] writes one through
 * [ai.rever.boss.mcp.McpPolicyEngine.setToolPolicyIfAbsent] - a dedicated add-only-if-absent
 * write, not the reactive approval path's own [ai.rever.boss.mcp.McpPolicyEngine.setToolPolicy].
 * The two calls answer different questions: the reactive path replaces whatever rule a tool has
 * when an operator explicitly clicks "Always Allow"/"Always Deny" *for that tool*, while this
 * path must never replace a rule that already exists, however it got there - including one an
 * explicit reactive decision wrote in the gap between this row being offered as a candidate and
 * the operator's click here reaching disk (review on #636).
 *
 * [rules] is a snapshot the caller re-derives from [ai.rever.boss.mcp.McpPolicyEngine.config] on
 * every recomposition, not a copy this dialog owns - a revoke calls back into [onRevoke] and
 * waits for the same state flow to reflect it, rather than mutating a local list that could drift
 * from what is actually on disk.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpPolicyManagerDialog(
    rules: Map<String, McpPolicyAction>,
    availableTools: List<McpToolIdentity>,
    onRevoke: suspend (toolName: String) -> Boolean,
    onSetPolicy: suspend (tool: McpToolIdentity, action: McpPolicyAction) -> McpProactivePolicyOutcome,
    onRefreshCandidates: () -> Unit,
    onDismiss: () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = with(LocalDensity.current) { windowSize.height.toDp() }
    val windowWidth = with(LocalDensity.current) { windowSize.width.toDp() }
    val maxHeight = if (windowHeight > 0.dp) (windowHeight - 32.dp).coerceAtLeast(1.dp) else 700.dp
    val maxWidth = if (windowWidth > 0.dp) (windowWidth - 32.dp).coerceAtLeast(1.dp) else 480.dp
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val scope = rememberCoroutineScope()
    // Which tool's revoke most recently failed to persist. A single slot, not a set: a
    // SUCCESSFUL revoke of any tool clears it, not only a retry of the same one, so two failures
    // in quick succession show only the most recent - accepted, since this is diagnostic rather
    // than an audit trail (the host log has that).
    var failedRevoke by remember { mutableStateOf<String?>(null) }
    // A DENY row asked to confirm once before it resets: resetting an ALLOW reduces standing
    // privilege, but resetting a DENY raises it, removing the one rule that beats session trust
    // (McpPolicyEngine.policyFor). Tracks at most one row at a time - switching to a different
    // row's button, or dismissing, drops any pending confirmation rather than carrying it silently.
    var confirmingDeny by remember { mutableStateOf<String?>(null) }

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).width(480.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Persistent MCP Tool Policies",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            "Saved from \"Always Allow\" / \"Always Deny\" in the tool approval dialog. " +
                                "Resetting a tool removes its saved rule - the next mutating call is " +
                                "governed by the default policy again, asking unless that default is " +
                                "itself Allow or Deny.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // The surrounding body scrolls as one region; Close stays outside it.
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                    ) {
                        if (rules.isEmpty()) {
                            Text(
                                text = "No saved rules. Default policies and session trust still apply.",
                                fontSize = 13.sp,
                                color = colors.textSecondary,
                            )
                        } else {
                            rules.toSortedMap().forEach { (toolName, action) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = toolName,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.textPrimary,
                                        )
                                        Text(
                                            text = action.name,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            // Both values represent a durable, disk-persisted rule -
                                            // the same category McpApprovalDialog's "Always Allow"/
                                            // "Always Deny" buttons are, which use warn/alert rather
                                            // than an ordinary success color.
                                            color = if (action == McpPolicyAction.DENY) colors.alert else colors.warn,
                                        )
                                        if (failedRevoke == toolName) {
                                            Text(
                                                // Session trust clears even on failure, but a saved ALLOW still
                                                // permits calls. Do not promise ASK while that durable rule remains.
                                                text =
                                                    "Saved rule unchanged; this tool's session trust was cleared. " +
                                                        "See the host log.",
                                                fontSize = 11.sp,
                                                color = colors.alert,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))

                                    fun revoke() {
                                        confirmingDeny = null
                                        scope.launch {
                                            failedRevoke = if (onRevoke(toolName)) null else toolName
                                        }
                                    }
                                    // Removing an ALLOW is a de-escalation and needs no confirmation.
                                    // Removing a DENY is the one control in this dialog that INCREASES
                                    // what the tool is allowed to do, so it gets a second tap instead of
                                    // firing on the first click like every other row's button does.
                                    if (action == McpPolicyAction.DENY && confirmingDeny != toolName) {
                                        TextButton(onClick = { confirmingDeny = toolName }) {
                                            Text("Remove denial", fontSize = 12.sp, color = colors.alert)
                                        }
                                    } else if (action == McpPolicyAction.DENY) {
                                        TextButton(onClick = { revoke() }) {
                                            Text("Confirm remove?", fontSize = 12.sp, color = colors.alert)
                                        }
                                    } else {
                                        TextButton(onClick = { revoke() }) {
                                            Text("Reset", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Set a rule proactively",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text =
                                "For a registered tool without a saved rule - no need to wait for an approval " +
                                    "prompt to lock one down. A tool name, not a specific plugin: another " +
                                    "plugin that later registers the same name inherits this rule too.",
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ProactivePolicySectionContent(availableTools, onSetPolicy, onRefreshCandidates, colors)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal),
                    ) {
                        Text("Close", color = colors.onSignal, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * A tool the policy engine can name a rule for - just enough identity for this dialog.
 *
 * [expectedRevocation] is [ai.rever.boss.mcp.McpPolicyEngine.revocationVersion] for
 * ([toolName], [providerId]) captured when this tool was offered as a proactive candidate - the
 * same generation-token pattern the reactive approval path uses (`McpToolRegistryImpl`'s own
 * `revocation` captured at invocation time), so a DENY or reset landing at either scope between
 * "this row was offered" and the write actually happening is caught by
 * [ai.rever.boss.mcp.McpPolicyEngine.setToolPolicyIfAbsent]'s own lock-protected recheck rather
 * than silently overwritten. That recheck also refuses outright if [toolName] has picked up *any*
 * rule since - not only a DENY, and not only through a revoke: an explicit ASK or ALLOW made
 * through the reactive approval dialog in the meantime never touches [expectedRevocation], so the
 * add-only-if-absent check is what actually protects it (review on #636).
 */
data class McpToolIdentity(
    val toolName: String,
    val providerId: String,
    val expectedRevocation: Long,
)

/**
 * [availableTools] is already filtered and sorted by the caller
 * ([ai.rever.boss.components.bars.horizontal.mcpProactivePolicyCandidates]); this renders it with
 * no scroll or height bound of its own so it shares the one outer scroll region
 * [McpPolicyManagerDialog] wraps both sections in.
 */
@Composable
private fun ProactivePolicySectionContent(
    availableTools: List<McpToolIdentity>,
    onSetPolicy: suspend (tool: McpToolIdentity, action: McpPolicyAction) -> McpProactivePolicyOutcome,
    onRefreshCandidates: () -> Unit,
    colors: BossColorScheme,
) {
    val scope = rememberCoroutineScope()
    // Like revoke feedback, retain only the latest attempted row outcome.
    var failedSet by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Allow raises standing privilege strictly further than anything else in this dialog can -
    // ASK-forever into unattended ALLOW for every future agent and argument set - so it gets the
    // same second-tap confirmation removing a DENY gets above, plus the risk/scope context
    // McpApprovalDialog shows before its own "Always Allow" (review on #636). Deny only ever
    // narrows what a tool can do, so it fires on the first tap like Reset does above.
    var confirmingAllow by remember(availableTools) { mutableStateOf<McpToolIdentity?>(null) }

    if (availableTools.isEmpty()) {
        Text(
            text = "Every registered tool already has a rule, or is disabled.",
            fontSize = 12.sp,
            color = colors.textSecondary,
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        availableTools.forEach { tool ->
            ProactivePolicyRow(
                tool = tool,
                confirming = confirmingAllow == tool,
                failureMessage = failedSet?.takeIf { it.first == tool.toolName }?.second,
                onArmAllow = { confirmingAllow = tool },
                onSet = { action ->
                    confirmingAllow = null
                    scope.launch {
                        val outcome = onSetPolicy(tool, action)
                        failedSet = outcome.proactivePolicyMessage()?.let { tool.toolName to it }
                        if (outcome is McpProactivePolicyOutcome.Refused) onRefreshCandidates()
                    }
                },
                colors = colors,
            )
        }
    }
}

@Composable
private fun ProactivePolicyRow(
    tool: McpToolIdentity,
    confirming: Boolean,
    failureMessage: String?,
    onArmAllow: () -> Unit,
    onSet: (McpPolicyAction) -> Unit,
    colors: BossColorScheme,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.toolName,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tool.providerId,
                    fontSize = 10.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (failureMessage != null) {
                    Text(
                        text = failureMessage,
                        fontSize = 10.sp,
                        color = colors.alert,
                    )
                }
            }

            if (confirming) {
                TextButton(onClick = { onSet(McpPolicyAction.ALLOW) }) {
                    Text("Confirm allow?", fontSize = 11.sp, color = colors.warn)
                }
            } else {
                TextButton(onClick = onArmAllow) {
                    Text("Allow", fontSize = 11.sp, color = colors.warn)
                }
            }
            TextButton(onClick = { onSet(McpPolicyAction.DENY) }) {
                Text("Deny", fontSize = 11.sp, color = colors.alert)
            }
        }
        if (confirming) {
            ProactiveAllowConfirmation(tool.toolName, colors)
        }
    }
}

/** The risk/scope context shown once Allow is armed, before the confirming second tap. */
@Composable
private fun ProactiveAllowConfirmation(
    toolName: String,
    colors: BossColorScheme,
) {
    val risk = remember(toolName) { DefaultMcpRiskEvaluator().evaluateRisk(toolName, McpToolArgs(emptyMap())) }
    if (McpMutatingToolCatalog.isMutating(toolName)) {
        Text("This tool performs mutations or external execution.", fontSize = 10.sp, color = colors.alert)
    }
    Text(
        text = "${risk.level}: ${risk.reason}",
        fontSize = 10.sp,
        color = colors.alert,
    )
    Text(
        text =
            "Applies to this tool name for all agents and arguments, across restarts " +
                "and replacement plugins, until reset above.",
        fontSize = 10.sp,
        color = colors.textSecondary,
    )
}

/** Refusals refresh candidates but always require a new operator confirmation. */
internal fun McpProactivePolicyOutcome.proactivePolicyMessage(): String? =
    when (this) {
        McpProactivePolicyOutcome.Saved -> {
            null
        }

        McpProactivePolicyOutcome.Refused -> {
            "Policy changed. Candidates refreshed; review the current policy before trying again."
        }

        McpProactivePolicyOutcome.PolicyUnreadable -> {
            "Policy file unreadable: all tools are withheld. Preserve a backup, repair the file, and restart BOSS."
        }

        McpProactivePolicyOutcome.Denied -> {
            "Current policy already denies this tool. Review the provider policy or defaults before adding a rule."
        }

        is McpProactivePolicyOutcome.Failed -> {
            "Could not save this rule. Check the host log and storage, then try again."
        }
    }
