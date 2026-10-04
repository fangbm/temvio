package dev.agenticscheduler.wear.capability

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.provider.ProviderProbeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WearProviderProbeTest {
    @Test fun currentStructuredProofIsReusedWithoutAnotherProbe() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; ProviderProbeResult.Supported }
        p.updateInput(input); p.refresh(); runCurrent()
        repeat(5) { assertEquals(ProviderProbeResult.Supported, p.capability(config, "1")) }
        assertEquals(1, calls)
        assertEquals(ProviderProbeResult.Unavailable("READINESS_CHANGED"), p.capability(config, "2"))
        p.updateInput(input.copy(bindingGeneration = "2")); p.refresh(); runCurrent()
        assertEquals(ProviderProbeResult.Supported, p.capability(config, "2")); assertEquals(2, calls)
        p.close()
    }

    @Test fun explicitRetryDiscardsOldTerminalResultWhileJoiningSingleFlight() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) {
            calls++; if (calls == 1) ProviderProbeResult.Unsupported else { delay(100); ProviderProbeResult.Supported }
        }
        p.updateInput(input); p.refresh(); runCurrent()
        assertEquals(ProviderProbeResult.Unsupported, p.capability(config, "1"))
        p.refresh(true); runCurrent()
        val joined = async { p.capability(config, "1") }; runCurrent()
        assertFalse(joined.isCompleted)
        advanceTimeBy(100); runCurrent()
        assertEquals(ProviderProbeResult.Supported, joined.await()); assertEquals(2, calls)
        p.close()
    }
    private val config = ProviderConfig(ProviderConfigId("01900000-0000-7000-8000-000000000001"), "https://provider.example/v1", "explicit-model", 2048, 512, false, true, null)
    private val input = WearProbeInput(config, "1", WearNetworkFacts(true, true, true), true)
    @Test fun singleFlightForegroundAndRepeatedRefresh() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; delay(500); ProviderProbeResult.Supported }
        p.updateInput(input); p.setForeground(true); runCurrent()
        repeat(20) { p.refresh(); p.setForeground(true) }; runCurrent(); assertEquals(1, calls)
        advanceTimeBy(500); runCurrent(); assertNull(p.state.value.failure); assertFalse(p.state.value.probing); p.close()
    }
    @Test fun totalDeadlineIsExactlyTenSeconds() = runTest {
        var cancelled = false
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        p.updateInput(input); p.refresh(); runCurrent(); advanceTimeBy(9999); runCurrent()
        assertTrue(p.state.value.probing); assertFalse(cancelled)
        advanceTimeBy(1); runCurrent(); assertTrue(cancelled); assertEquals(WearProbeFailure.TIMEOUT, p.state.value.failure)
        assertEquals(12000L, p.state.value.nextRefreshAtMillis); p.close()
    }
    @Test fun foregroundRetryBackoffIsBoundedAndNeverOverlaps() = runTest {
        val starts = mutableListOf<Long>(); var active = 0; var maximum = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) {
            starts += testScheduler.currentTime; active++; maximum = maxOf(maximum, active)
            delay(1); active--; ProviderProbeResult.Unavailable("HTTP_503")
        }
        p.updateInput(input); p.setForeground(true); runCurrent()
        for (backoff in listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L)) {
            advanceTimeBy(1); runCurrent(); assertEquals(testScheduler.currentTime + backoff, p.state.value.nextRefreshAtMillis)
            advanceTimeBy(backoff); runCurrent()
        }
        assertEquals(listOf(0L, 2001L, 6002L, 14003L, 30004L, 60005L, 90006L), starts)
        assertEquals(1, maximum); p.close()
    }
    @Test fun authenticationStopsUntilExplicitRetry() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; ProviderProbeResult.Unavailable("HTTP_401") }
        p.updateInput(input); p.setForeground(true); runCurrent(); advanceTimeBy(60000)
        repeat(3) { p.refresh(); p.setForeground(true) }; runCurrent(); assertEquals(1, calls)
        assertEquals(WearProbeFailure.AUTHENTICATION, p.state.value.failure); assertNull(p.state.value.nextRefreshAtMillis)
        p.refresh(explicitRetry = true); runCurrent(); assertEquals(2, calls); p.close()
    }
    @Test fun unsupportedToolsAndBadConfigStopAutomaticRefresh() = runTest {
        for (result in listOf(ProviderProbeResult.Unsupported, ProviderProbeResult.Unavailable("HTTP_400"))) {
            var calls = 0
            val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; result }
            p.updateInput(input); p.setForeground(true); runCurrent(); advanceTimeBy(60000); p.refresh(); runCurrent()
            assertEquals(1, calls); assertFalse(p.state.value.failure!!.retryable); p.close()
        }
    }
    @Test fun bindingCredentialAndConfigChangesResetStop() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; if (calls == 1) ProviderProbeResult.Unsupported else ProviderProbeResult.Supported }
        p.updateInput(input); p.refresh(); runCurrent()
        p.updateInput(input.copy(bindingGeneration = "2", config = config.copy(model = "changed-model"))); p.refresh(); runCurrent()
        assertEquals(2, calls); assertNull(p.state.value.failure); p.close()
    }
    @Test fun staleCompletionCannotOverwriteNewGeneration() = runTest {
        var calls = 0; var active = 0; var maximum = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) {
            calls++; active++; maximum = maxOf(maximum, active)
            if (calls == 1) withContext(NonCancellable) { delay(1000) }
            active--; if (calls == 1) ProviderProbeResult.Unsupported else ProviderProbeResult.Supported
        }
        p.updateInput(input); p.refresh(); runCurrent()
        val update = launch { p.updateInput(input.copy(bindingGeneration = "2")) }; runCurrent()
        p.refresh(); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1000); runCurrent(); update.join(); p.refresh(); runCurrent()
        assertEquals(2, calls); assertNull(p.state.value.failure); assertEquals(1, maximum); p.close()
    }
    @Test fun offlineAndDisabledInputsNeverProbeOrReplay() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; ProviderProbeResult.Supported }
        p.updateInput(input.copy(eligible = false)); p.setForeground(true); runCurrent(); assertEquals(0, calls)
        p.updateInput(input); p.refresh(); runCurrent(); assertEquals(1, calls)
        // There is no command/write callback in this service; restoration performs only a probe.
        p.updateInput(input.copy(network = WearNetworkFacts.OFFLINE, eligible = false)); p.refresh(); runCurrent(); assertEquals(1, calls); p.close()
    }
    @Test fun backgroundDoesNotRunAnUnboundedRetryLoop() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; ProviderProbeResult.Unavailable("NETWORK_FAILURE") }
        p.updateInput(input); p.refresh(); runCurrent(); advanceTimeBy(100000); runCurrent(); assertEquals(1, calls)
        p.refresh(); runCurrent(); assertEquals(2, calls); p.close()
    }
    @Test fun networkAndPreferenceChangesCannotResetTerminalStop() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; ProviderProbeResult.Unavailable("HTTP_401") }
        p.updateInput(input); p.refresh(); runCurrent()
        p.updateInput(input.copy(network = WearNetworkFacts.OFFLINE, eligible = false)); p.refresh(); runCurrent()
        p.updateInput(input); p.setForeground(true); runCurrent(); assertEquals(1, calls)
        p.updateInput(input.copy(eligible = false)); p.updateInput(input); p.refresh(); runCurrent()
        assertEquals(1, calls); assertEquals(WearProbeFailure.AUTHENTICATION, p.state.value.failure); p.close()
    }
    @Test fun routeChangeDoesNotBypassRetryBackoff() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; ProviderProbeResult.Unavailable("NETWORK_FAILURE") }
        p.updateInput(input); p.refresh(); runCurrent()
        p.updateInput(input.copy(network = WearNetworkFacts.OFFLINE, eligible = false)); p.updateInput(input)
        p.refresh(); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(2000); p.refresh(); runCurrent(); assertEquals(2, calls); p.close()
    }
    @Test fun foregroundRouteRestorationSchedulesRemainingBackoff() = runTest {
        var calls = 0
        val p = WearProviderProbe(backgroundScope, { testScheduler.currentTime }) { calls++; if (calls == 1) ProviderProbeResult.Unavailable("NETWORK_FAILURE") else ProviderProbeResult.Supported }
        p.updateInput(input); p.setForeground(true); runCurrent(); advanceTimeBy(1000)
        p.updateInput(input.copy(network = WearNetworkFacts.OFFLINE, eligible = false)); p.updateInput(input); p.refresh(); runCurrent()
        assertEquals(1, calls); advanceTimeBy(999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls); assertNull(p.state.value.failure); p.close()
    }
}
