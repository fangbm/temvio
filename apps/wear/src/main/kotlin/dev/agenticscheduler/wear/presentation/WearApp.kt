package dev.agenticscheduler.wear.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.wear.agent.*
import dev.agenticscheduler.agent.history.ProviderConfigId

/** Small Watch graph; infrastructure/lifecycle ownership remains in the platform root. */
@Composable
fun WearApp(service: ConflictAwareSourceFactReadService, runtime: WearAgentRuntimeComposition?, localEntryEnabled: Boolean,
    initializing: Boolean, initializationFailure: Boolean, onInitializeAgent: () -> Unit,
    syncFailure: String?, onRetrySync: () -> Unit,
    credentialSetupVisible: Boolean = false, credentialSetup: WearCredentialSetupPresentation? = null,
    onOpenCredential: (ProviderConfigId) -> Unit = {}, onCloseCredential: () -> Unit = {}, providerRefresh: Int = 0,
    credentialSetupOpening: Boolean = false) {
    val nav = remember { WearNavigation() }
    if (credentialSetupVisible) {
        if (credentialSetup != null) WearCredentialSetupScreen(credentialSetup, onCloseCredential)
        else {
            BackHandler(onBack = onCloseCredential)
            WearPage("wear-credential-setup") {
                item { WearHeading(if (credentialSetupOpening) "Opening credential setup" else "Credential setup unavailable") }
                item { WearCopy(if (credentialSetupOpening) "Reading saved local Provider setup…" else "An active configured Watch sync account and an existing HTTPS Provider are required. Return to Status to review setup.") }
                item { WearAction("Back", "credential-back", onClick = onCloseCredential) }
            }
        }
        return
    }
    fun open(destination: WearDestination) { nav.open(destination); onInitializeAgent() }
    when {
        nav.destination == WearDestination.TODAY -> WearTodayRoute(service, syncFailure,
            runtime?.readiness?.readiness?.collectAsState()?.value?.effectiveAiEntryEnabled ?: localEntryEnabled,
            onAgent = { open(if (localEntryEnabled) WearDestination.AGENT else WearDestination.STATUS) },
            onStatus = { open(WearDestination.STATUS) }, onRetrySync = onRetrySync)
        runtime != null -> WearAgentRoute(runtime, nav, syncFailure, onRetrySync, onOpenCredential, providerRefresh)
        else -> {
            BackHandler { nav.back() }
            WearPage("wear-opening") {
                item { WearHeading("Agent setup") }
                item { WearCopy(if (initializationFailure) "Local Agent setup is unavailable" else "Opening local Agent…") }
                if (!initializing) item { WearAction("Retry setup", "setup-retry", onClick = onInitializeAgent) }
                item { WearAction("Today", "wear-back", onClick = { nav.back() }) }
            }
        }
    }
}

@Composable
fun WearStartupScreen(blocked: Boolean) {
    WearPage("wear-startup") {
        item { WearHeading(if (blocked) "Sync setup needs attention" else "Opening local schedule…") }
        item { WearCopy(if (blocked) "Restore the device credential or check the configured account and server." else "Connecting to your secure sync space…") }
    }
}
