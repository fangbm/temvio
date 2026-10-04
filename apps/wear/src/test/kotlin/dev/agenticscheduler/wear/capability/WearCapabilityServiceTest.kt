package dev.agenticscheduler.wear.capability

import kotlin.test.*

class WearCapabilityServiceTest {
    private class Platform : WearSpeechPlatform {
        override var apiLevel = 33
        override val platformSupported = true
        override val textInputSupported = true
        var permissionValue = SpeechPermission.NOT_REQUESTED
        var service = true; var permissions = 0; var starts = 0; var queries = 0; var closes = 0
        var listener: ((SpeechCandidateResult) -> Unit)? = null
        var support: ((OnDeviceSttAvailability) -> Unit)? = null
        var requested: ((Boolean) -> Unit)? = null
        override fun permission() = permissionValue
        override fun onDeviceServiceAvailable() = service
        override fun checkLanguage(language: String, result: (OnDeviceSttAvailability) -> Unit): AutoCloseable { queries++; support = result; return AutoCloseable {} }
        override fun requestMicrophoneFromUserAction(result: (Boolean) -> Unit) { permissions++; requested = result }
        override fun recognizeOnDevice(language: String, result: (SpeechCandidateResult) -> Unit): AutoCloseable { starts++; listener = result; return AutoCloseable { closes++ } }
    }
    @Test fun passiveInspectionNeverRequestsPermissionOrAudio() {
        val p = Platform(); val s = WearCapabilityService(p, "en-US"); repeat(4) { s.inspectCapability() }
        assertEquals(0, p.permissions); assertEquals(0, p.starts); assertEquals(0, p.queries)
        assertEquals(SpeechPermission.NOT_REQUESTED, s.facts.value.speechPermission); s.close()
    }
    @Test fun api30AndNoServiceLeaveTextSupported() {
        for ((api, service) in listOf(30 to true, 31 to false, 33 to false)) {
            val p = Platform().apply { apiLevel = api; this.service = service }; val s = WearCapabilityService(p, "en-US")
            s.inspectCapability(); assertEquals(OnDeviceSttAvailability.UNSUPPORTED, s.facts.value.onDeviceSttAvailability); assertTrue(s.facts.value.aiEntrySupported)
            var result: SpeechCandidateResult? = null; s.useSpeechFromExplicitUserAction { result = it }
            assertEquals(SpeechCandidateResult.ServiceUnavailable, result); assertEquals(0, p.starts); assertEquals(0, p.permissions); s.close()
        }
    }
    @Test fun api31And32DoNotAssumeLanguageSupport() {
        for (api in listOf(31, 32)) {
            val p = Platform().apply { apiLevel = api; permissionValue = SpeechPermission.GRANTED }; val s = WearCapabilityService(p, "zh-CN")
            s.inspectCapability(); assertEquals(OnDeviceSttAvailability.LANGUAGE_UNVERIFIED, s.facts.value.onDeviceSttAvailability); assertEquals(0, p.queries); s.close()
        }
    }
    @Test fun actualSupportResultControlsApi33LanguageFacts() {
        val p = Platform().apply { permissionValue = SpeechPermission.GRANTED }; val s = WearCapabilityService(p, "en-US")
        for (availability in OnDeviceSttAvailability.entries) { s.inspectCapability(); p.support!!(availability); assertEquals(availability, s.facts.value.onDeviceSttAvailability) }; s.close()
    }
    @Test fun denialIsNotUnsupportedAndTextRemains() {
        val p = Platform(); val s = WearCapabilityService(p, "en-US"); var result: SpeechCandidateResult? = null
        s.useSpeechFromExplicitUserAction { result = it }; assertEquals(1, p.permissions)
        p.permissionValue = SpeechPermission.DENIED; p.requested!!(false)
        assertEquals(SpeechCandidateResult.PermissionDenied, result); assertEquals(SpeechPermission.DENIED, s.facts.value.speechPermission)
        assertEquals(OnDeviceSttAvailability.LANGUAGE_UNVERIFIED, s.facts.value.onDeviceSttAvailability); assertTrue(s.facts.value.aiEntrySupported); assertEquals(0, p.starts); s.close()
    }
    @Test fun permissionRevocationDoesNotEraseProvenLanguageCapability() {
        val p = Platform().apply { permissionValue = SpeechPermission.GRANTED }; val s = WearCapabilityService(p, "en-US")
        s.inspectCapability(); p.support!!(OnDeviceSttAvailability.AVAILABLE)
        p.permissionValue = SpeechPermission.DENIED; s.inspectCapability()
        assertEquals(OnDeviceSttAvailability.AVAILABLE, s.facts.value.onDeviceSttAvailability)
        assertEquals(SpeechPermission.DENIED, s.facts.value.speechPermission); s.close()
    }
    @Test fun knownUnsupportedLanguageCannotStartSpeech() {
        val p = Platform().apply { permissionValue = SpeechPermission.GRANTED }; val s = WearCapabilityService(p, "en-US")
        s.inspectCapability(); p.support!!(OnDeviceSttAvailability.UNSUPPORTED)
        var result: SpeechCandidateResult? = null; s.useSpeechFromExplicitUserAction { result = it }
        assertEquals(SpeechCandidateResult.ServiceUnavailable, result); assertEquals(0, p.starts); s.close()
    }
    @Test fun knownDownloadPendingLanguageDoesNotStartOrDownload() {
        val p = Platform().apply { permissionValue = SpeechPermission.GRANTED }; val s = WearCapabilityService(p, "en-US")
        s.inspectCapability(); p.support!!(OnDeviceSttAvailability.TEMPORARILY_UNAVAILABLE)
        var result: SpeechCandidateResult? = null; s.useSpeechFromExplicitUserAction { result = it }
        assertEquals(SpeechCandidateResult.ServiceUnavailable, result); assertEquals(0, p.starts); assertTrue(s.facts.value.textInputSupported); s.close()
    }
    @Test fun explicitGrantStartsOnlyLocalRecognition() {
        val p = Platform(); val s = WearCapabilityService(p, "en-US"); var result: SpeechCandidateResult? = null
        s.useSpeechFromExplicitUserAction { result = it }; p.permissionValue = SpeechPermission.GRANTED; p.requested!!(true)
        assertEquals(1, p.starts); p.listener!!(SpeechCandidateResult.TextCandidate("candidate only")); assertEquals(SpeechCandidateResult.TextCandidate("candidate only"), result); assertEquals(1, p.closes); s.close()
    }
    @Test fun repeatedStartAndCancelDiscardLateResult() {
        val p = Platform().apply { permissionValue = SpeechPermission.GRANTED }; val s = WearCapabilityService(p, "en-US")
        val results = mutableListOf<SpeechCandidateResult>(); s.useSpeechFromExplicitUserAction(results::add); val stale = p.listener!!
        s.useSpeechFromExplicitUserAction(results::add); assertEquals(SpeechCandidateResult.Busy, results.single()); assertEquals(1, p.starts)
        s.cancel(); stale(SpeechCandidateResult.TextCandidate("late")); assertEquals<List<SpeechCandidateResult>>(listOf(SpeechCandidateResult.Busy, SpeechCandidateResult.Cancelled), results); s.close()
    }
    @Test fun languageChangeDiscardsRecognitionAndSupportCallbacks() {
        val p = Platform().apply { permissionValue = SpeechPermission.GRANTED }; val s = WearCapabilityService(p, "en-US")
        val results = mutableListOf<SpeechCandidateResult>(); s.useSpeechFromExplicitUserAction(results::add); val old = p.listener!!; val oldSupport = p.support!!
        s.selectLanguageFromUserAction("zh-CN"); old(SpeechCandidateResult.TextCandidate("stale")); oldSupport(OnDeviceSttAvailability.AVAILABLE)
        assertEquals("zh-CN", s.facts.value.selectedLanguageTag); assertEquals(OnDeviceSttAvailability.LANGUAGE_UNVERIFIED, s.facts.value.onDeviceSttAvailability)
        assertEquals<List<SpeechCandidateResult>>(listOf(SpeechCandidateResult.Cancelled), results); s.close()
    }
    @Test fun permissionRevocationRejectsResult() {
        val p = Platform().apply { permissionValue = SpeechPermission.GRANTED }; val s = WearCapabilityService(p, "en-US")
        var result: SpeechCandidateResult? = null; s.useSpeechFromExplicitUserAction { result = it }; p.permissionValue = SpeechPermission.DENIED
        p.listener!!(SpeechCandidateResult.TextCandidate("must not accept")); assertEquals(SpeechCandidateResult.PermissionDenied, result); s.close()
    }
    @Test fun errorsAndCancelPendingPermissionCannotSubmitAnything() {
        val p = Platform(); val s = WearCapabilityService(p, "en-US"); val results = mutableListOf<SpeechCandidateResult>()
        s.useSpeechFromExplicitUserAction(results::add); s.cancel(); p.permissionValue = SpeechPermission.GRANTED; p.requested!!(true)
        assertEquals(0, p.starts); assertEquals<List<SpeechCandidateResult>>(listOf(SpeechCandidateResult.Cancelled), results)
        s.useSpeechFromExplicitUserAction(results::add); p.listener!!(SpeechCandidateResult.Failed); assertEquals(SpeechCandidateResult.Failed, results.last()); s.close()
    }
}
