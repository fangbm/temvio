package dev.agenticscheduler.wear.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.agenticscheduler.agent.history.AgentToolCall

/** The captured persisted call/preview remains the exact input to existing revalidation. */
@Composable
fun WearConfirmationScreen(call: AgentToolCall, busy: Boolean, onDecision: (Boolean) -> Unit, onLater: () -> Unit) {
    val readable = hasReadablePreview(call.previewJson)
    val references = previewReferenceFacts(call.previewJson)
    var showReferences by remember(call.id,call.previewJson) { mutableStateOf(false) }
    WearPage("wear-confirmation") {
        item { WearHeading("Review action") }
        item { WearHeading(toolTitle(call.name), Modifier.testTag("confirmation-preview")) }
        item { WearCopy("Requires your confirmation on this Watch. Current facts are checked again before execution.") }
        previewFacts(call.previewJson).forEach { fact -> item { WearCopy("${fact.label}: ${fact.value}") } }
        if(references.isNotEmpty()) item { WearAction(if(showReferences) "Hide reference details" else "Reference details", "preview-references", onClick={showReferences=!showReferences}) }
        if(showReferences) references.forEachIndexed { index,fact -> item { WearCopy("${fact.label}: ${fact.value}",Modifier.testTag("preview-reference-$index")) } }
        if (!readable) item { WearCopy("Recorded preview unavailable. Confirm is disabled. Keep this action pending; confirmation requires its exact details on this Watch.") }
        item { WearAction("Confirm on Watch", "agent-confirm", enabled = !busy && readable, primary = true, onClick = { onDecision(true) }) }
        item { WearAction("Deny", "agent-deny", enabled = !busy && call.previewJson != null, onClick = { onDecision(false) }) }
        item { WearAction("Review later", "confirmation-later", onClick = onLater) }
        item { WearCopy("Back or review later keeps the pending action. It does not deny or execute it.") }
    }
}
