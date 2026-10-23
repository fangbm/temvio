package dev.agenticscheduler.wear

import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.time.*
import dev.agenticscheduler.wear.agent.*
import dev.agenticscheduler.wear.capability.*
import dev.agenticscheduler.wear.presentation.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*
import org.junit.*
import org.junit.Test
import org.junit.Before
import java.io.File
import org.junit.Assert.*
import kotlin.time.Instant

/** Real round Wear rendering/interaction. Synthetic presentation fixtures supplement retained runtime/Room E2E. */
class WearFinalUxInstrumentedTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ready = WearReadiness(WearCapabilityFacts(true,true,OnDeviceSttAvailability.UNSUPPORTED,SpeechPermission.NOT_REQUESTED,"und"),
        true,WearProviderFacts(true,true,true,false,false,false),true,null)
    private val tid = AgentThreadId("01900000-0000-7000-8000-000000000701")
    private val mid = AgentMessageId("01900000-0000-7000-8000-000000000702")
    private val call = AgentToolCall(AgentToolCallId("01900000-0000-7000-8000-000000000703"),tid,mid,0,"task.create","{}",
        AgentToolCallState.WAITING_CONFIRMATION,"""{"before":null,"title":"Read a chapter","priority":"NORMAL","estimatedMinutes":30,"remainingMinutes":null,"deadline":null}""")
    private val date = LocalDate(2026,10,24)
    private val displayConfig=ProviderConfig(ProviderConfigId("01900000-0000-7000-8000-000000000721"),
        "https://synthetic.invalid/v1","Synthetic chat profile",8192,2048,false,true,null)
    @Before fun watchOnly() { assertTrue("Must use actual Wear AVD", context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) }
    private fun scroll(page: String, tag: String) { ui.onNodeWithTag(page).performScrollToNode(hasTestTag(tag)) }
    private fun assertSafe(page: String, tag: String) {
        scroll(page,tag)
        ui.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val rect=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val config=context.resources.configuration
        val cx=config.screenWidthDp/2f; val cy=config.screenHeightDp/2f; val radius=minOf(cx,cy)
        for(x in listOf(rect.left.value,rect.right.value)) for(y in listOf(rect.top.value,rect.bottom.value))
            assertTrue("$tag is clipped by round edge: $rect",(x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius+2f)
    }
    @Test fun todayAgentStatusRetryAndOverflowAreReachableAndPassive() {
        var agent=0;var status=0;var retry=0
        val model=WearTodayModel(date,listOf(WearScheduleRow(CalendarSourceRef.Event(EventId("01900000-0000-7000-8000-000000000711")),"Synthetic meeting","Event","14:00 · UTC")),12,1,1,0)
        ui.setContent { WearProductTheme { WearTodayScreen(model,"AUTHENTICATION_REQUIRED",false,{agent++},{status++},{retry++}) } }
        assertEquals(0,agent);assertEquals(0,status);assertEquals(0,retry)
        assertSafe("wear-today","today-agent");ui.onNodeWithTag("today-agent").assertTextContains("Agent setup").performClick();assertEquals(1,agent)
        assertSafe("wear-today","sync-retry");ui.onNodeWithTag("sync-retry").performClick();assertEquals(1,retry)
        ui.onNodeWithTag("wear-today").performScrollToNode(hasText("Calendar overlap",substring=true))
        ui.onNodeWithTag("wear-today").performScrollToNode(hasText("Business Sync conflict",substring=true))
        ui.onNodeWithTag("wear-today").performScrollToNode(hasText("12 more upcoming",substring=true))
        assertSafe("wear-today","open-status");ui.onNodeWithTag("open-status").performClick();assertEquals(1,status)
    }
    @Test fun disabledEntryStillAllowsSetupWithoutEnablingOrSending() {
        var enables=0;var sends=0;val nav=WearNavigation(WearDestination.AGENT)
        ui.setContent { WearProductTheme { WearAgentScreen(WearAgentSessionState(draft="draft"),ready.copy(userEnabledAiEntry=false,provider=ready.provider.copy(bindingExists=false)),null,
            {},{sends++},{},{_,_,_->},{enables++},{},{},{},{},navigation=nav) } }
        scroll("wear-agent","agent-send");ui.onNodeWithTag("agent-send").assertIsNotEnabled()
        scroll("wear-agent","open-status");ui.onNodeWithTag("open-status").performClick()
        ui.onNodeWithTag("wear-status").assertExists();assertEquals(0,enables);assertEquals(0,sends)
        ui.onNodeWithTag("wear-status").performScrollToNode(hasText("Select a Provider before checking credential availability"))
        ui.onNodeWithTag("wear-status").performScrollToNode(hasText("Selected Provider route available"))
        assertSafe("wear-status","entry-toggle");ui.onNodeWithTag("entry-toggle").performClick();assertEquals(1,enables);assertEquals(0,sends)
    }
    @Test fun todayEmptyAndEachSemanticSourceTimeKindRenderWithoutMutatingAnything() {
        val model=mutableStateOf(WearTodayModel(date,emptyList(),0,0,0,0))
        ui.setContent {WearProductTheme {WearTodayScreen(model.value,null,true,{error("Unexpected Agent action")},{},{})}}
        ui.onNodeWithTag("wear-today").performScrollToNode(hasText("Nothing scheduled",substring=true))
        ui.runOnIdle { model.value=todayModel(date,TimeZone.UTC,CalendarProjectionResult(emptyList<CalendarItem>().toImmutableList(),
            emptyList<CalendarConflict>().toImmutableList(),listOf(CalendarProjectionIssue.SyncConflictUnprojectable(listOf("conflict"),"UNPROJECTABLE")).toImmutableList())) }
        ui.onNodeWithTag("wear-today").performScrollToNode(hasText("Business Sync conflict",substring=true))
        ui.onNodeWithTag("wear-today").performScrollToNode(hasText("No reliable schedule loaded"))
        val time=ZonedTimeRange(Instant.parse("2026-10-24T14:00:00Z"),Instant.parse("2026-10-24T15:00:00Z"),TimeZone.UTC)
        val event=CalendarSourceRef.Event(EventId("01900000-0000-7000-8000-000000000711"))
        val items=listOf(CalendarItem.Zoned(event,"Exact event",time),CalendarItem.AllDay(event,"All day",AllDayRange(date,date.plus(1,DateTimeUnit.DAY))),
            CalendarItem.Floating(event,"Floating event",FloatingTimeRange(LocalDateTime(2026,10,24,8,0),LocalDateTime(2026,10,24,9,0))),
            CalendarItem.DateOnly(CalendarSourceRef.Exam(ExamId("01900000-0000-7000-8000-000000000712")),"Date-only exam",date),
            CalendarItem.Zoned(CalendarSourceRef.CourseSession(dev.agenticscheduler.domain.academic.CourseOccurrenceKey(CourseScheduleRuleId("01900000-0000-7000-8000-000000000714"),dev.agenticscheduler.domain.academic.AcademicWeekNumber(1))),"Course",time),
            CalendarItem.Zoned(CalendarSourceRef.FocusBlock(FocusBlockId("01900000-0000-7000-8000-000000000713")),"Focus",time))
        for(item in items){
            val row=scheduleRow(item,TimeZone.UTC)
            ui.runOnIdle{model.value=WearTodayModel(date,listOf(row),0,0,0,0)}
            ui.onNodeWithTag("wear-today").performScrollToNode(hasText("${row.kind} · ${row.title}"))
            ui.onNodeWithText("${row.kind} · ${row.title}").assertIsDisplayed()
            ui.onNodeWithTag("wear-today").performScrollToNode(hasText(row.time));ui.onNodeWithText(row.time).assertExists()
        }
    }
    @Test fun exactConfirmationBackThemeAndRecompositionAreNotDecisions() {
        val dark=mutableStateOf(false);var confirms=0;var denies=0
        val nav=WearNavigation(WearDestination.AGENT)
        ui.setContent { WearProductTheme(dark.value) { WearAgentScreen(WearAgentSessionState(tid,pendingDraft(),calls=listOf(call)),ready,null,
            {},{error("Unexpected Send")},{},{id,preview,approved->assertEquals(call.id,id);assertEquals(call.previewJson,preview);if(approved)confirms++ else denies++},
            {},{},{},{},{},navigation=nav) } }
        ui.waitUntil(15000){nav.confirmation!=null};assertSafe("wear-confirmation","agent-confirm");assertSafe("wear-confirmation","agent-deny")
        ui.runOnIdle {dark.value=true};assertEquals(0,confirms);assertEquals(0,denies)
        scroll("wear-confirmation","confirmation-later");ui.onNodeWithTag("confirmation-later").performClick()
        assertNull(nav.confirmation);assertEquals(0,confirms);assertEquals(0,denies)
        scroll("wear-agent","review-pending");ui.onNodeWithTag("review-pending").performClick()
        assertEquals(call.id,nav.confirmation?.id);assertEquals(call.previewJson,nav.confirmation?.previewJson)
        scroll("wear-confirmation","agent-confirm");ui.onNodeWithTag("agent-confirm").performClick();assertEquals(1,confirms)
    }
    private fun pendingDraft()="Unsent draft"
    @Test fun missingRecordedPreviewCannotEnableConfirmationOrInventReplacement() {
        var decisions=0;val nav=WearNavigation(WearDestination.AGENT)
        ui.setContent {WearProductTheme {WearAgentScreen(WearAgentSessionState(tid,calls=listOf(call.copy(previewJson=null))),ready,null,
            {},{},{},{_,_,_->decisions++},{},{},{},{},{},navigation=nav)}}
        ui.waitUntil(15000){nav.confirmation!=null}
        scroll("wear-confirmation","agent-confirm");ui.onNodeWithTag("agent-confirm").assertIsNotEnabled()
        scroll("wear-confirmation","agent-deny");ui.onNodeWithTag("agent-deny").assertIsNotEnabled()
        scroll("wear-confirmation","confirmation-later");ui.onNodeWithTag("confirmation-later").performClick()
        assertEquals(0,decisions);assertNull(nav.confirmation)
    }
    @Test fun referenceDetailsAreExplicitSecondaryPresentationAndNeverExecute() {
        val id="01900000-0000-7000-8000-000000000711"
        val preview="""{"before":{"id":"$id","title":"Before"},"after":{"id":"$id","title":"After"}}"""
        val pending=call.copy(name="task.update",previewJson=preview);val nav=WearNavigation(WearDestination.AGENT);var decisions=0
        ui.setContent {WearProductTheme {WearAgentScreen(WearAgentSessionState(tid,calls=listOf(pending)),ready,null,
            {},{},{},{_,_,_->decisions++},{},{},{},{},{},navigation=nav)}}
        ui.waitUntil(15000){nav.confirmation!=null};ui.onNodeWithText(id,substring=true).assertDoesNotExist()
        scroll("wear-confirmation","preview-references");ui.onNodeWithTag("preview-references").performClick()
        scroll("wear-confirmation","preview-reference-0");ui.onNodeWithTag("preview-reference-0").assertTextContains(id,substring=true)
        assertEquals(preview,nav.confirmation?.previewJson);assertEquals(0,decisions)
    }

    @Test fun unsupportedSpeechAndUndKeepTextSendAvailableAndSetupPassive() {
        var sends=0;var microphone=0;var speech=0;var inspections=0
        val platform=object:WearSpeechPlatform {
            override val apiLevel=33;override val platformSupported=true;override val textInputSupported=true
            override fun permission()=SpeechPermission.NOT_REQUESTED
            override fun onDeviceServiceAvailable():Boolean { inspections++;return true }
            override fun checkLanguage(language:String,result:(OnDeviceSttAvailability)->Unit):AutoCloseable {assertNotEquals("und",language);return AutoCloseable{} }
            override fun requestMicrophoneFromUserAction(result:(Boolean)->Unit){microphone++;result(false)}
            override fun recognizeOnDevice(language:String,result:(SpeechCandidateResult)->Unit):AutoCloseable {speech++;return AutoCloseable{} }
        }
        val capability=WearCapabilityService(platform,"und");val nav=WearNavigation(WearDestination.AGENT)
        ui.setContent {val facts by capability.facts.collectAsState();WearProductTheme {WearAgentScreen(WearAgentSessionState(draft="editable"),ready.copy(capability=facts),null,
            {},{sends++},{},{_,_,_->error("Unexpected confirm")},{},{},{capability.useSpeechFromExplicitUserAction{}},capability::cancel,
            capability::selectLanguageFromUserAction,navigation=nav)} }
        scroll("wear-agent","speech-start");ui.onNodeWithTag("speech-start").assertIsNotEnabled();assertEquals(0,microphone);assertEquals(0,speech)
        scroll("wear-agent","agent-send");ui.onNodeWithTag("agent-send").assertIsEnabled()
        scroll("wear-agent","open-status");ui.onNodeWithTag("open-status").performClick();assertEquals(0,microphone);assertEquals(0,sends)
        scroll("wear-status","speech-language");ui.onNodeWithTag("speech-language").performTextInput("en-US")
        scroll("wear-status","speech-language-select");ui.onNodeWithTag("speech-language-select").performClick();assertTrue(inspections>0);assertEquals(0,microphone)
        scroll("wear-status","status-agent");ui.onNodeWithTag("status-agent").performClick()
        scroll("wear-agent","speech-start");ui.onNodeWithTag("speech-start").performClick();assertEquals(1,microphone);assertEquals(0,sends);assertEquals(0,speech)
        ui.runOnIdle {capability.close()}
    }
    @Test fun committedToolSuccessRemainsVisibleBesideFailedProviderContinuation() {
        val result=AgentToolResult(AgentToolResultId("01900000-0000-7000-8000-000000000704"),tid,call.id,0,AgentToolResultStatus.SUCCESS,"{}",
            listOf(dev.agenticscheduler.sync.MutationId("01900000-0000-7000-8000-000000000705")))
        ui.setContent {WearProductTheme {WearAgentScreen(WearAgentSessionState(tid,phase=WearAgentUiPhase.PROVIDER_UNAVAILABLE,
            calls=listOf(call.copy(state=AgentToolCallState.COMPLETED)),results=listOf(result)),ready,null,{},{},{},{_,_,_->},{},{},{},{},{})}}
        ui.onNodeWithTag("wear-agent").performScrollToNode(hasText("Recorded successful business changes remain committed",substring=true))
        scroll("wear-agent","tool-result");ui.onNodeWithTag("tool-result").assertTextContains("Succeeded")
    }
    @Test fun actualWearD10FinalScreenshotCandidates() {
        val config=context.resources.configuration;val args=InstrumentationRegistry.getArguments()
        val width=config.screenWidthDp;val height=config.screenHeightDp;val font=(config.fontScale*100).toInt()
        args.getString("captureWidth")?.let{assertEquals(it.toInt(),width);assertEquals(it.toInt(),height)}
        args.getString("captureFont")?.let{assertEquals((it.toFloat()*100).toInt(),font)}
        assertTrue("Acceptance must use round Wear hardware profile",config.isScreenRound)
        val capture=mutableStateOf<Pair<String,Boolean>?>(null)
        ui.setContent {WearProductTheme(capture.value?.second?:true) {
            capture.value?.first?.let { scene -> key(scene,capture.value?.second) {
                when(scene) {
                    "today","sync-stopped" -> WearTodayScreen(todayModel(date,TimeZone.UTC,projection()),if(scene=="sync-stopped")"AUTHENTICATION_REQUIRED" else null,true,{},{},{})
                    "confirmation","confirmation-actions" -> WearConfirmationScreen(call,false,{}, {})
                    "status" -> WearStatusScreen(ready.copy(userEnabledAiEntry=false),displayConfig,{},{},{},{},{})
                    "credential-ready","credential-comparison","credential-approved","credential-installed" -> {
                        val state=when(scene){
                            "credential-ready"->CredentialSetupFixtures.ready()
                            "credential-comparison"->ProviderTargetState.ComparisonReady(CredentialSetupFixtures.comparison())
                            "credential-approved"->ProviderTargetState.UserApproved(CredentialSetupFixtures.flow)
                            else->ProviderTargetState.Installed(CredentialSetupFixtures.flow,ProviderTargetAcknowledgement(ProviderTargetAckStatus.PENDING))
                        }
                        WearCredentialSetupScreen(WearCredentialSetupView(state,CredentialSetupFixtures.binding.model,
                            enteredCode=if(scene=="credential-comparison"&&width>192)"87654321" else ""),{},{},{},{})
                    }
                    else -> {
                        val r=when(scene){"offline"->ready.copy(networkReachable=false);"chat-only"->ready.copy(providerProbeFailure=WearProbeFailure.UNSUPPORTED_TOOLS);else->ready}
                        val messages=listOf(AgentMessage(mid,tid,0,AgentMessageRole.USER,"What is next?",0),
                            AgentMessage(AgentMessageId("01900000-0000-7000-8000-000000000706"),tid,1,AgentMessageRole.ASSISTANT,"Your next event is at 14:00.",1))
                        val result=AgentToolResult(AgentToolResultId("01900000-0000-7000-8000-000000000704"),tid,call.id,0,AgentToolResultStatus.STALE,"{}")
                        WearAgentScreen(WearAgentSessionState(tid,"Ask about my schedule",phase=WearAgentUiPhase.IDLE,messages=if(scene=="conversation")messages else emptyList(),
                            calls=if(scene=="tool-result")listOf(call.copy(state=AgentToolCallState.FAILED))else emptyList(),results=if(scene=="tool-result")listOf(result)else emptyList()),r,displayConfig,
                            {},{},{},{_,_,_->},{},{},{},{},{})
                    }
                }
            }}
        }}
        val scenes=if(font>=190) listOf("confirmation-actions","status","credential-approved") else if(width<=192)
            listOf("today","ready","offline","confirmation","confirmation-actions","status","chat-only","sync-stopped","credential-ready","credential-comparison","credential-approved","credential-installed")
            else listOf("today","conversation","tool-result","confirmation","confirmation-actions","status","credential-comparison","credential-installed")
        val out=File(context.getExternalFilesDir(null),"d10-05-screenshots").also{it.mkdirs()}
        for(scene in scenes)for(dark in if(scene.startsWith("credential-"))listOf(scene !in listOf("credential-ready","credential-installed")) else if(font>=190 || scene in listOf("chat-only","sync-stopped"))listOf(true)else listOf(false,true)) {
            ui.runOnIdle{capture.value=scene to dark};ui.waitForIdle();ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
            when(scene){
                "confirmation-actions"->assertSafe("wear-confirmation","agent-confirm")
                "status"->assertSafe("wear-status","entry-toggle")
                "ready","offline","chat-only"->assertSafe("wear-agent","agent-send")
                "tool-result"->scroll("wear-agent","tool-result")
                "sync-stopped"->assertSafe("wear-today","sync-retry")
                "conversation"->ui.onNodeWithTag("wear-agent").performScrollToNode(hasText("Your next event is at 14:00."))
                "today"->{assertSafe("wear-today","today-agent");ui.onNodeWithTag("wear-today").performScrollToNode(hasText("Event · Research session"))}
                "credential-ready"->assertSafe("wear-credential-setup","credential-source-0")
                "credential-approved"->assertSafe("wear-credential-setup","credential-install")
                "credential-installed"->assertSafe("wear-credential-setup","credential-retry")
                "credential-comparison"->if(width>192)assertSafe("wear-credential-setup","credential-code") else {
                    scroll("wear-credential-setup","credential-watch-code");ui.onNodeWithTag("credential-watch-code").assertIsDisplayed().assertTextContains("1234 5678")
                }
            }
            // First prove Send bounds, then show the actual blocker/limitation in its review image.
            if(scene=="offline") scroll("wear-agent","readiness")
            if(scene=="chat-only") scroll("wear-agent","chat-only")
            // Compose idleness does not fence the emulator's SurfaceFlinger transaction.
            // Settle theme/scroll frames before capturing the actual physical round display.
            ui.mainClock.advanceTimeBy(500);ui.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            android.os.SystemClock.sleep(500)
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(out,"wear-${width}x$height-font$font-${if(dark)"dark"else"light"}-$scene.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        android.util.Log.i("D1005Evidence","Wear emulator acceptance: api=${android.os.Build.VERSION.SDK_INT}; round=${config.isScreenRound}; dp=${width}x$height; density=${context.resources.displayMetrics.densityDpi}; font=$font; no physical Watch claim")
    }
    private fun projection():CalendarProjectionResult {
        val source=CalendarSourceRef.Event(EventId("01900000-0000-7000-8000-000000000711"))
        return CalendarProjectionResult(listOf(CalendarItem.Zoned(source,"Research session",ZonedTimeRange(Instant.parse("2026-10-24T14:00:00Z"),Instant.parse("2026-10-24T15:00:00Z"),TimeZone.UTC)),
            CalendarItem.DateOnly(CalendarSourceRef.Exam(ExamId("01900000-0000-7000-8000-000000000712")),"Exam",date),CalendarItem.AllDay(source,"Reading day",AllDayRange(date,date.plus(1,DateTimeUnit.DAY)))).toImmutableList(),
            emptyList<CalendarConflict>().toImmutableList(),emptyList<CalendarProjectionIssue>().toImmutableList())
    }
}
