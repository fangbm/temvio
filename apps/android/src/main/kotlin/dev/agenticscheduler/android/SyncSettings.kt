package dev.agenticscheduler.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import dev.agenticscheduler.ui.ActionButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.application.sync.AgentConversationSyncSettings
import dev.agenticscheduler.application.sync.AgentConversationSyncSetting
import dev.agenticscheduler.application.sync.AgentHistoryExportAvailability
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun AndroidConversationSyncControls(settings: AgentConversationSyncSettings) {
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<AgentConversationSyncSetting>>(emptyList()) }
    var acknowledgement by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var eligibility by remember { mutableStateOf<Map<SyncSpaceId, AgentHistoryExportAvailability>>(emptyMap()) }
    LaunchedEffect(settings) { choices = settings.settings() }
    LaunchedEffect(settings, choices) { eligibility = choices.mapNotNull { choice -> settings.historyExportAvailability(choice.syncSpaceId)?.let { choice.syncSpaceId to it } }.toMap() }
    Column(modifier = Modifier.testTag("agent-conversation-sync")) {
        Text("Agent conversation/history sync")
        Text("Separate from Agent-origin business writes (V2). Off by default; older/downgraded devices cannot display V3 history. The server does not attest client versions.")
        Text("Production transmission remains unavailable pending the local-data security release gate.")
        if (choices.isEmpty()) Text("No active enrolled SyncSpace.")
        Row {
            Checkbox(checked = acknowledgement, onCheckedChange = { acknowledgement = it },
                modifier = Modifier.testTag("agent-conversation-v3-ack"))
            Text("I confirm all active enrolled devices support V3.")
        }
        choices.forEach { choice ->
            Text("${choice.syncSpaceId.value}: consent ${if (choice.consent) "ON" else "OFF"}; history recovery ${choice.recoveryState}.")
            eligibility[choice.syncSpaceId]?.let { Text("Verified local history: ${it.eligibleTurns} complete turn(s); ${it.skippedLegacyThreads} legacy thread(s) are excluded.") }
            if (choice.recoveryState.name in listOf("REQUIRED", "RUNNING", "INCOMPLETE"))
                Text("Conversation history is incomplete until retained history and keys are recovered.")
            Button(enabled = !saving && (choice.consent || acknowledgement),
                modifier = Modifier.testTag("agent-conversation-toggle"),
                onClick = {
                    scope.launch {
                        saving = true
                        try {
                            settings.setFromUser(choice.syncSpaceId, !choice.consent, acknowledgement)
                            acknowledgement = false
                            choices = settings.settings()
                            status = "Consent choice saved. No history was exported."
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { status = "Unable to save conversation sync choice. Check enrollment and V3 acknowledgement."
                        } finally { saving = false }
                    }
                }) { Text(if (choice.consent) "Turn conversation sync OFF" else "Explicitly enable conversation sync") }
            Button(enabled = !saving && choice.consent && (eligibility[choice.syncSpaceId]?.eligibleTurns ?: 0) > 0,
                modifier = Modifier.testTag("agent-history-explicit-export"), onClick = {
                    scope.launch {
                        saving = true
                        try {
                            val result = settings.exportHistoryFromUser(choice.syncSpaceId)
                            status = "History export queued: ${result.newlyQueuedFacts} new fact(s), ${result.previouslyQueuedFacts} already queued; ${result.skippedLegacyThreads} legacy thread(s) excluded."
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { status = "Unable to prepare verified Agent history export. No network upload was started."
                        } finally { saving = false }
                    }
                }) { Text("Export verified history") }
        }
        Button(enabled = !saving, onClick = { scope.launch { choices = settings.settings(); acknowledgement = false } }) { Text("Refresh enrollment/history status") }
        status?.let { Text(it) }
    }
}
