package dev.agenticscheduler.wear.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.agenticscheduler.application.sync.ProviderTargetState
import androidx.wear.compose.material3.SwipeToDismissBox

@Composable
fun WearCredentialSetupScreen(presentation: WearCredentialSetupPresentation, onBack: () -> Unit) {
    val view by presentation.view.collectAsState()
    WearCredentialSetupScreen(view, presentation::chooseSource, presentation::enterCode, presentation::act, onBack)
}

@Composable
fun WearCredentialSetupScreen(view: WearCredentialSetupView, onSource: (dev.agenticscheduler.sync.DeviceId) -> Unit,
    onCode: (String) -> Unit, onAction: (CredentialSetupAction) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val copy = view.copy
    SwipeToDismissBox(onDismissed = onBack, userSwipeEnabled = true) { background -> if (!background) WearPage("wear-credential-setup") {
        item { WearHeading("Credential setup") }
        item { WearCopy(view.providerLabel) }
        item { WearHeading(copy.title) }
        item { WearCopy(copy.detail, Modifier.testTag("credential-status")) }
        val state = view.state
        if (state is ProviderTargetState.Ready) {
            item { WearCopy("Sending devices") }
            state.provisioners.forEachIndexed { index, id ->
                item { WearAction("…${id.value.takeLast(6)}", "credential-source-$index", enabled = !view.busy, onClick = { onSource(id) }) }
            }
            item { WearCopy(view.selectedSource?.let { "Selected: ${sendingDeviceLabel(it)}" } ?: "No sending device selected", Modifier.testTag("credential-selected-source")) }
        }
        if (state is ProviderTargetState.ComparisonReady) {
            item { WearCopy(sendingDeviceLabel(state.comparison.flow.provisioner)) }
            item { WearCopy("This Watch") }
            item { WearHeading(state.comparison.code.chunked(4).joinToString(" "), Modifier.testTag("credential-watch-code")) }
            item { WearCopy("Enter the 8 digits shown on the sending device") }
            item { WearDraftField(view.enteredCode, onCode, "credential-code", "Code shown on sending device") }
        }
        if (state is ProviderTargetState.Installed) state.acknowledgement.failure?.let { failure ->
            item { WearCopy(credentialFailureCopy(failure)) }
        }
        if (view.busy) item { WearCopy("Working…") }
        copy.actions.forEach { action -> item {
            WearAction(when(action) {
                CredentialSetupAction.REQUEST -> if (state is ProviderTargetState.Expired) "Request again" else "Request"
                CredentialSetupAction.FETCH -> if (state is ProviderTargetState.Reserved && state.comparisonRefreshRequired) "Reload codes" else "Check"
                CredentialSetupAction.APPROVE -> "Approve match"
                CredentialSetupAction.INSTALL -> "Install"
                CredentialSetupAction.RETRY -> if (state is ProviderTargetState.Installed) "Retry" else if (state is ProviderTargetState.Reserved) "Retry request" else "Retry setup"
                CredentialSetupAction.RECOVER -> "Resume setup"
            }, "credential-${action.name.lowercase()}", enabled = !view.busy && when(action) {
                CredentialSetupAction.APPROVE -> view.canApprove
                CredentialSetupAction.REQUEST -> view.selectedSource != null || state is ProviderTargetState.Expired
                else -> true
            }, primary = action == CredentialSetupAction.APPROVE || action == CredentialSetupAction.INSTALL, onClick = { onAction(action) })
        } }
        item { WearAction("Back", "credential-back", onClick = onBack) }
    } }
}
