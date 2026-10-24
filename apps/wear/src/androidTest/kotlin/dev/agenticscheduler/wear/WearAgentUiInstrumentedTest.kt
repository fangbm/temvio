package dev.agenticscheduler.wear

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.material3.MaterialTheme
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.wear.agent.*
import dev.agenticscheduler.wear.capability.*
import dev.agenticscheduler.wear.presentation.*
import org.junit.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Presentation mapping only. Real execution acceptance is WearAgentRuntimeInstrumentedTest. */
@RunWith(AndroidJUnit4::class)
class WearAgentUiInstrumentedTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val ready = WearReadiness(WearCapabilityFacts(true, true, OnDeviceSttAvailability.UNSUPPORTED, SpeechPermission.NOT_REQUESTED, "und"), true,
        WearProviderFacts(true, true, true, false, false, false), true, null)
    @Test fun blockerAndTypedRuntimeReasonsAreVisibleWithoutEnablingSend() {
        val readiness = mutableStateOf(ready)
        val state = mutableStateOf(WearAgentSessionState(draft = "explicit text"))
        ui.setContent { WearProductTheme { WearAgentScreen(state.value, readiness.value, null, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}) } }
        val blocked = listOf(ready.copy(userEnabledAiEntry = false), ready.copy(networkReachable = false),
            ready.copy(provider = ready.provider.copy(bindingExists = false, exactBindingApproved = false)),
            ready.copy(provider = ready.provider.copy(credentialRequired = true, matchingSecretAvailable = false)))
        for (value in blocked) {
            ui.runOnIdle { readiness.value = value }
            ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("readiness")); ui.onNodeWithTag("readiness").assertTextContains(readinessCopy(value.runtimeState), substring = true)
            ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("agent-send")); ui.onNodeWithTag("agent-send").assertIsNotEnabled()
        }
        ui.runOnIdle { readiness.value = ready }
        ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("agent-send")); ui.onNodeWithTag("agent-send").assertIsEnabled()
        for (phase in WearAgentUiPhase.entries) {
            ui.runOnIdle { state.value = state.value.copy(phase = phase, busy = phase in setOf(WearAgentUiPhase.THINKING,WearAgentUiPhase.TOOL_PROPOSED,WearAgentUiPhase.TOOL_RUNNING)) }
            ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("agent-status")); ui.onNodeWithTag("agent-status").assertTextContains(phaseCopy(phase))
        }
        ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("speech-start")); ui.onNodeWithTag("speech-start").assertIsNotEnabled()
    }
    @Test fun plainHttpWarningIsVisibleAndLoopbackHasNoFalseSecureClaim() {
        val config = mutableStateOf(ProviderConfig(ProviderConfigId("01900000-0000-7000-8000-000000000323"), "http://192.168.1.3:8080/v1", "local-model", 8192, 2048, false, true, null))
        ui.setContent { WearProductTheme { WearAgentScreen(WearAgentSessionState(), ready, config.value, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}) } }
        ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("http-warning")); ui.onNodeWithTag("http-warning").assertTextContains("Requests may travel unencrypted over HTTP.")
        ui.runOnIdle { config.value = config.value.copy(baseUrl = "http://127.0.0.1:8080/v1") }
        ui.onNodeWithTag("http-warning").assertDoesNotExist()
        ui.runOnIdle { config.value = config.value.copy(baseUrl = "https://provider.example/v1") }
        ui.onNodeWithTag("http-warning").assertDoesNotExist()
    }
    @Test fun chatOnlyModeTruthfullyShowsLimitationAndAllowsSendButFailuresDoNot() {
        val readiness = mutableStateOf(ready.copy(providerProbeFailure = WearProbeFailure.UNSUPPORTED_TOOLS))
        ui.setContent { WearProductTheme { WearAgentScreen(WearAgentSessionState(draft = "explicit chat"), readiness.value, null,
            {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}) } }
        ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("readiness")); ui.onNodeWithTag("readiness").assertTextContains("Ready", substring = true)
        ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("chat-only")); ui.onNodeWithTag("chat-only").assertTextContains("Chat only. Actions are unavailable with this Provider.")
        ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("agent-send")); ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("agent-send")); ui.onNodeWithTag("agent-send").assertIsEnabled()
        for (failure in listOf(WearProbeFailure.AUTHENTICATION, WearProbeFailure.INVALID_CONFIG, WearProbeFailure.NETWORK)) {
            ui.runOnIdle { readiness.value = ready.copy(providerProbeFailure = failure) }
            ui.onNodeWithTag("chat-only").assertDoesNotExist()
            ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("agent-send")); ui.onNodeWithTag("agent-send").assertIsNotEnabled()
        }
        ui.runOnIdle { readiness.value = ready.copy(providerProbeFailure = WearProbeFailure.UNSUPPORTED_TOOLS, networkReachable = false) }
        ui.onNodeWithTag("wear-agent").performScrollToNode(hasTestTag("agent-send")); ui.onNodeWithTag("agent-send").assertIsNotEnabled()
    }
}
