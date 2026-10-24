package dev.agenticscheduler.wear.presentation

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.time.*
import dev.agenticscheduler.wear.agent.WearAgentUiPhase
import dev.agenticscheduler.wear.capability.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Instant

class WearPresentationTest {
    private val date = LocalDate(2026, 10, 24)
    private val zone = TimeZone.UTC
    private val event = CalendarSourceRef.Event(EventId("01900000-0000-7000-8000-000000000711"))
    private val call = AgentToolCall(AgentToolCallId("01900000-0000-7000-8000-000000000101"),
        AgentThreadId("01900000-0000-7000-8000-000000000102"), AgentMessageId("01900000-0000-7000-8000-000000000103"),
        0, "task.create", "{}", AgentToolCallState.WAITING_CONFIRMATION, "{\"title\":\"Watch task\"}")

    @Test fun defaultEntryIsTodayAndBackDoesNotInventHistory() {
        val n = WearNavigation(); assertEquals(WearDestination.TODAY, n.destination); assertFalse(n.back())
        n.open(WearDestination.AGENT); assertTrue(n.back()); assertEquals(WearDestination.TODAY, n.destination)
    }
    @Test fun statusReturnsToItsActualOrigin() {
        val n = WearNavigation(); n.open(WearDestination.STATUS); n.back(); assertEquals(WearDestination.TODAY, n.destination)
        n.open(WearDestination.AGENT); n.open(WearDestination.STATUS); n.back(); assertEquals(WearDestination.AGENT, n.destination)
    }
    @Test fun confirmationRetainsExactPersistedIdentityAndPreviewUntilClosed() {
        val n = WearNavigation(WearDestination.AGENT); n.review(call)
        assertSame(call, n.confirmation); assertEquals(call.previewJson, n.confirmation?.previewJson)
        assertTrue(n.back()); assertNull(n.confirmation); assertEquals(WearDestination.AGENT, n.destination)
        n.review(call); assertSame(call, n.confirmation) // Navigation never changed the immutable pending fact.
    }
    @Test fun primaryNavigationLeavesOnlyPresentationOverlay() {
        val n = WearNavigation(WearDestination.AGENT); n.review(call); n.open(WearDestination.STATUS)
        assertNull(n.confirmation); assertEquals(AgentToolCallState.WAITING_CONFIRMATION, call.state)
    }
    @Test fun everyReadinessReasonHasDistinctProductCopy() {
        val values = WearProviderRuntimeState.entries.map(::readinessCopy)
        assertEquals(values.size, values.distinct().size)
        WearProviderRuntimeState.entries.forEach { assertFalse(readinessCopy(it).contains(it.name)) }
    }
    @Test fun everyPhaseAndToolOutcomeHasExplicitCopy() {
        assertEquals(WearAgentUiPhase.entries.size, WearAgentUiPhase.entries.map(::phaseCopy).distinct().size)
        assertEquals(AgentToolResultStatus.entries.size, AgentToolResultStatus.entries.map(::toolResultCopy).distinct().size)
        assertContains(toolResultCopy(AgentToolResultStatus.STALE), "new proposal")
        assertContains(phaseCopy(WearAgentUiPhase.PROVIDER_UNAVAILABLE), "Provider response")
    }
    @Test fun previewDisplaysRecordedValuesWithoutRawIdsOrRewritingSnapshot() {
        val snapshot = """{"title":"Exact title","priority":"NORMAL","estimatedMinutes":30,"taskId":"private-id","deadline":null}"""
        val facts = previewFacts(snapshot)
        assertTrue(facts.any { it.label == "Title" && it.value == "Exact title" })
        assertTrue(facts.any { it.label == "Estimated minutes" && it.value == "30" })
        assertFalse(facts.any { it.value.contains("private-id") }); assertContains(snapshot, "private-id")
    }
    @Test fun beforeAfterImageFactsRetainActualDifferences() {
        val facts = previewFacts("""{"beforeImageJson":"{\"title\":\"Before\"}","afterImageJson":"{\"title\":\"After\"}"}""")
        assertEquals(listOf("Before", "After"), facts.map { it.value })
        assertEquals(listOf("Before · Title", "After · Title"), facts.map { it.label })
    }
    @Test fun domainReferencesRemainAvailableOnlyInSecondaryInspection() {
        val json="""{"taskId":"exact-task-reference","title":"Task","credentialReference":"never-display","providerConfigId":"never-display"}"""
        assertFalse(previewFacts(json).any{it.value=="exact-task-reference"})
        assertEquals(listOf(WearPreviewFact("Task reference","exact-task-reference")),previewReferenceFacts(json))
    }
    @Test fun unavailablePreviewIsHonestAndNeverInventsSuccess() {
        assertContains(previewFacts("broken").single().value, "unavailable")
        assertContains(previewFacts(null).single().value, "unavailable")
    }
    @Test fun missingPrimitiveOrIdentityOnlyPreviewIsNotReadableConfirmation() {
        assertFalse(hasReadablePreview(null));assertFalse(hasReadablePreview("broken"));assertFalse(hasReadablePreview("{}"))
        assertFalse(hasReadablePreview("""{"taskId":"reference"}"""));assertTrue(hasReadablePreview(call.previewJson))
    }
    @Test fun partialOrMalformedNestedPreviewCannotEnableInformedConfirmation() {
        var nested="""{"title":"Exact"}""";repeat(20){nested="""{"child":$nested}"""}
        assertFalse(hasReadablePreview(nested))
        assertFalse(hasReadablePreview("""{"beforeImageJson":"{"}"""))
        assertFalse(previewFacts(null).single().value.contains("larger device"))
    }
    @Test fun todayIsBoundedAndSeparatesMoreOverlapAndBusinessConflict() {
        val items = (1..100).map { CalendarItem.DateOnly(event, "Exam $it", date) }
        val result = CalendarProjectionResult(items.toImmutableList(), listOf(CalendarConflict(event, event)).toImmutableList(), emptyList<CalendarProjectionIssue>().toImmutableList())
        val model = todayModel(date, zone, result, listOf("a", "a", "b"))
        assertEquals(3, model.rows.size); assertEquals(97, model.moreCount); assertEquals(1, model.overlapCount); assertEquals(2, model.syncConflictCount)
    }
    @Test fun dateOnlyAndAllDayNeverBecomeInstants() {
        assertContains(scheduleRow(CalendarItem.DateOnly(CalendarSourceRef.Exam(ExamId("01900000-0000-7000-8000-000000000712")), "Exam", date), zone).time, "Date only")
        val row = scheduleRow(CalendarItem.AllDay(event, "Day", AllDayRange(date, date.plus(1, DateTimeUnit.DAY))), zone)
        assertContains(row.time, "All day"); assertContains(row.time, "exclusive"); assertFalse(row.time.contains("00:00"))
    }
    @Test fun sourceKindsRemainEventFocusCourseAndExam() {
        val sources = listOf(event, CalendarSourceRef.FocusBlock(FocusBlockId("01900000-0000-7000-8000-000000000713")),
            CalendarSourceRef.CourseSession(dev.agenticscheduler.domain.academic.CourseOccurrenceKey(CourseScheduleRuleId("01900000-0000-7000-8000-000000000714"),dev.agenticscheduler.domain.academic.AcademicWeekNumber(1))),
            CalendarSourceRef.Exam(ExamId("01900000-0000-7000-8000-000000000712")))
        assertEquals(listOf("Event","Focus","Course","Exam"), sources.map { scheduleRow(CalendarItem.DateOnly(it,"Synthetic",date),zone).kind })
    }
    @Test fun unprojectableTaskAndCalendarConflictsRemainVisibleAndDistinctFromOverlap() {
        val ids=readConflictIds(dev.agenticscheduler.application.history.ConflictAwareRead.Unprojectable(listOf("task","shared"),"UNPROJECTABLE"))
        val p=CalendarProjectionResult(emptyList<CalendarItem>().toImmutableList(),emptyList<CalendarConflict>().toImmutableList(),
            listOf(CalendarProjectionIssue.SyncConflictUnprojectable(listOf("calendar","shared"),"UNPROJECTABLE")).toImmutableList())
        val m=todayModel(date,zone,p,ids);assertEquals(3,m.syncConflictCount);assertEquals(0,m.overlapCount);assertEquals(1,m.issueCount)
        assertTrue(readConflictIds(dev.agenticscheduler.application.history.ConflictAwareRead.Projected(emptyList<Unit>())).isEmpty())
    }
    @Test fun cancelledForegroundDisplayDoesNotClaimRunningOrRewriteRecordedResults() {
        val stopped=dev.agenticscheduler.wear.agent.WearAgentSessionState(phase=WearAgentUiPhase.TOOL_RUNNING)
        assertContains(sessionStatusCopy(stopped),"Request stopped")
        assertEquals("Action running",sessionStatusCopy(stopped.copy(busy=true)))
        assertTrue(stopped.results.isEmpty())
    }
    @Test fun emptyWindowStaysEmptyWithoutInventingSchedule() {
        val p=CalendarProjectionResult(emptyList<CalendarItem>().toImmutableList(),emptyList<CalendarConflict>().toImmutableList(),emptyList<CalendarProjectionIssue>().toImmutableList())
        val m=todayModel(date,zone,p);assertTrue(m.rows.isEmpty());assertEquals(0,m.moreCount);assertEquals(0,m.overlapCount);assertEquals(0,m.syncConflictCount)
    }
    @Test fun zonedCrossMidnightAndFloatingRemainDistinct() {
        val row = scheduleRow(CalendarItem.Zoned(event, "Night", ZonedTimeRange(Instant.parse("2026-10-24T23:30:00Z"), Instant.parse("2026-10-25T00:30:00Z"), zone)), zone)
        assertContains(row.time, "2026-10-25"); assertContains(row.time, "UTC")
        val floating = scheduleRow(CalendarItem.Floating(event, "Floating", FloatingTimeRange(LocalDateTime(2026,10,24,8,0),LocalDateTime(2026,10,24,9,0))), zone)
        assertContains(floating.time, "Floating"); assertFalse(floating.time.contains("UTC"))
    }
    @Test fun undAndDeniedNeverClaimAgentUnavailable() {
        val facts = WearCapabilityFacts(true,true,OnDeviceSttAvailability.UNSUPPORTED,SpeechPermission.NOT_REQUESTED,"und")
        assertContains(speechCopy(facts), "Choose a speech language"); assertTrue(facts.aiEntrySupported)
        val denied = facts.copy(selectedLanguageTag="en-US",speechPermission=SpeechPermission.DENIED)
        assertContains(speechCopy(denied), "permission denied"); assertTrue(denied.aiEntrySupported)
    }
    @Test fun speechServiceLanguagePermissionReasonsStayDistinct() {
        val facts = WearCapabilityFacts(true,true,OnDeviceSttAvailability.AVAILABLE,SpeechPermission.GRANTED,"en-US")
        val values = listOf(facts, facts.copy(speechPermission=SpeechPermission.NOT_REQUESTED), facts.copy(speechPermission=SpeechPermission.DENIED),
            facts.copy(onDeviceSttAvailability=OnDeviceSttAvailability.UNSUPPORTED), facts.copy(onDeviceSttAvailability=OnDeviceSttAvailability.LANGUAGE_UNVERIFIED),
            facts.copy(onDeviceSttAvailability=OnDeviceSttAvailability.TEMPORARILY_UNAVAILABLE))
        assertEquals(values.size, values.map(::speechCopy).distinct().size)
    }
    @Test fun invalidEndpointDoesNotCrashPresentationOrClaimTransportSecurity() {
        val config=ProviderConfig(ProviderConfigId("01900000-0000-7000-8000-000000000720"),"not a valid url","synthetic",8192,2048,false,true,null)
        assertFalse(dev.agenticscheduler.wear.agent.requiresHttpDisclosure(config))
        assertEquals("Provider is unavailable",readinessCopy(WearProviderRuntimeState.PROVIDER_UNAVAILABLE))
    }
}
