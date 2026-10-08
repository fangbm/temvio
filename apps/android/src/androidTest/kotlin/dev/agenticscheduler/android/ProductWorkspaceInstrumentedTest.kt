package dev.agenticscheduler.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.fixtures.D10ProductFixtureGraph
import dev.agenticscheduler.presentation.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.hours

class ProductWorkspaceInstrumentedTest {
    @get:Rule val compose=createComposeRule()
    @Test fun nativeMorePlannerPreviewBackDoesNotCommitThenReturnsToMore() = fixture {f,_ ->
        val p=f.workspace();val nav=AndroidNavigation();val count=runBlocking {f.base.journal.timeline().size}
        compose.setContent {app(f,nav,p)}
        compose.onNodeWithTag("android-nav-MORE").performClick()
        compose.onNodeWithText("Planner",substring=false).performScrollTo().performClick()
        compose.waitUntil(10000) {p.loaded}
        compose.onNodeWithText("Research hours",substring=false).performClick()
        for((tag,value) in listOf("planner-reference" to f.now.toString(),"planner-horizon-start" to f.now.toString(),"planner-horizon-end" to "2026-10-06T17:00:00Z")) {
            compose.onNodeWithTag("planner-workspace").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performTextReplacement(value)
        }
        compose.onNodeWithTag("planner-request-preview").performScrollTo().performClick()
        compose.waitUntil(10000) {p.preview is PlannerPreview.Applicable && !p.busy}
        compose.onNodeWithTag("planner-preview").assertExists();compose.waitForIdle()
        back();if(p.preview!=null) back()
        compose.runOnIdle {check(p.preview==null) {"Preview still open"};check(nav.current==AndroidDestination.PLANNER) {"Unexpected route ${nav.current}"}}
        runBlocking {check(f.base.journal.timeline().size==count)}
        back();if(nav.current==AndroidDestination.PLANNER) back() // Remaining IME owns Back before the shell.
        compose.runOnIdle {check(nav.current==AndroidDestination.MORE)};check(f.base.providerRequests==0)
    }
    @Test fun nativePlannerApplyUsesActualGroupedMutation() = fixture {f,_ ->
        val p=f.workspace();runBlocking {p.refresh();p.draft=f.request();p.requestPreview()}
        val count=runBlocking {f.base.journal.timeline().size}
        compose.setContent {app(f,AndroidNavigation().also {it.open(AndroidDestination.PLANNER)},p)}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("planner-preview").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("planner-preview").performScrollToNode(hasTestTag("planner-apply-time"))
        compose.onNodeWithTag("planner-apply-time").performScrollTo().performTextReplacement(f.now.toString())
        compose.onNodeWithTag("planner-apply").performScrollTo().performClick()
        compose.waitUntil(10000) {p.preview==null && !p.busy}
        runBlocking {check(f.base.journal.timeline().size==count+1);check(f.base.tasks.observeFocusBlocks().first().isNotEmpty())};check(f.base.providerRequests==0)
    }
    @Test fun nativeHistoryUndoRequiresConfirmationAndRetainsOriginal() = fixture {f,_ ->
        val task=runBlocking {f.base.tasks.observeTasks().first().single()}
        val update=runBlocking {f.base.taskEditor.update(UpdateTaskInput(task.id,"Native edited notes",task.status,task.priority,2.hours,kotlin.time.Duration.ZERO,2.hours,null))} as EditingResult.Success
        val h=f.history();runBlocking {h.select(update.mutationId!!.value)}
        val count=runBlocking {f.base.journal.timeline().size};val nav=AndroidNavigation().also {it.open(AndroidDestination.HISTORY)}
        compose.setContent {app(f,nav,history=h)}
        compose.onNodeWithTag("history-undo").performScrollTo().performClick()
        compose.onNodeWithText("Keep change",substring=false).performClick()
        runBlocking {check(f.base.journal.timeline().size==count)}
        compose.onNodeWithTag("history-undo").performScrollTo().performClick()
        compose.onNodeWithText("Confirm compensating Undo",substring=false).performClick()
        compose.waitUntil(10000) {!h.busy && h.message?.startsWith("Compensating")==true}
        runBlocking {check(f.base.journal.timeline().size==count+1);check(f.base.tasks.getTask(task.id)==task);check(f.base.journal.mutation(update.mutationId!!.value)!=null)}
        back();compose.runOnIdle {check(h.detail==null);check(nav.current==AndroidDestination.HISTORY)}
        back();compose.runOnIdle {check(nav.current==AndroidDestination.MORE)}
    }
    @Test fun nativeSyncStoppedRetryAndConflictInspectionDoNotResolve() = fixture {f,_ ->
        runBlocking {f.enroll();f.profileConflict()};val s=f.sync();var retries=0
        val count=runBlocking {f.base.journal.timeline().size}
        compose.setContent {app(f,AndroidNavigation().also {it.open(AndroidDestination.SYNC_SECURITY)},sync=s,stopped="HTTP_401",retry={retries++})}
        compose.waitUntil(10000) {s.loaded}
        compose.onNodeWithTag("sync-explicit-retry").performScrollTo().performClick();compose.runOnIdle {check(retries==1)}
        compose.onNodeWithTag("sync-security").performScrollToNode(hasText("Inspect business conflict",substring=false))
        compose.onNodeWithText("Inspect business conflict").performScrollTo().performClick()
        compose.waitUntil(10000) {s.selected!=null}
        compose.onNodeWithText("Resolution unavailable",substring=false).performScrollTo().assertExists()
        back();compose.runOnIdle {check(s.selected==null)}
        runBlocking {check(f.base.journal.timeline().size==count);check(f.conflicts.listOpen(f.space).size==1);check(!f.base.conversationSettings.settings().single().consent)}
    }
    @Test fun nativeSettingsProfileCancelAndThemeStayLocal() = fixture {f,_ ->
        val p=f.workspace();val count=runBlocking {f.base.journal.timeline().size}
        compose.setContent {app(f,AndroidNavigation().also {it.open(AndroidDestination.SETTINGS)},p)}
        compose.onNodeWithText("Use Dark theme",substring=false).performClick();compose.onNodeWithText("Use Light theme",substring=false).assertExists()
        compose.onNodeWithText("Open planning profiles",substring=false).performScrollTo().performClick();compose.waitUntil(10000) {p.loaded}
        compose.onNodeWithText("New Unconfigured profile",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("profile-name").performTextReplacement("Unsaved native profile")
        back();if(compose.onAllNodesWithText("Discard unsaved changes?").fetchSemanticsNodes().isEmpty()) back()
        compose.onNodeWithText("Discard draft",substring=false).performClick()
        runBlocking {check(f.base.journal.timeline().size==count)};check(f.base.providerRequests==0)
    }
    @Test fun nativeCanonicalProfileConfigureFromSettingsSavesUserMutationOnly() = fixture {f,_ ->
        val p=f.workspace();val count=runBlocking {f.base.journal.timeline().size}
        compose.setContent {app(f,AndroidNavigation().also {it.open(AndroidDestination.SETTINGS)},p)}
        compose.onNodeWithText("Open planning profiles",substring=false).performScrollTo().performClick()
        compose.waitUntil(10000) {p.loaded}
        compose.onNodeWithText("Explicit draft profile",substring=false).performClick()
        compose.onNodeWithTag("profile-edit-open").performScrollTo().performClick()
        compose.onNodeWithTag("profile-configure").performClick()
        for((tag,value) in listOf("profile-edit-zone" to "UTC","profile-edit-minimum" to "25m","profile-edit-preferred" to "50m","profile-edit-maximum" to "90m")) {
            compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithText("Visible only - does not block planning",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("profile-edit-save").performClick()
        compose.waitUntil(10000) {p.profileEditor==null && !p.busy}
        runBlocking {
            check(f.base.profiles.get(f.unconfigured.id)!!.configuration is dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Configured)
            check(f.base.journal.timeline().size==count+1)
            check(f.base.journal.timeline().last().operation.origin==dev.agenticscheduler.sync.MutationOrigin.User)
            check(f.base.tasks.observeFocusBlocks().first().isEmpty())
        }
        check(p.preview==null);check(f.base.providerRequests==0)
    }
    @Test fun nativeProfileBackRequiresDiscardAndStaleReloadIsExplicit() = fixture {f,_ ->
        val p=f.workspace();runBlocking {p.refresh();p.openProfileEditor(f.configured)}
        val nav=AndroidNavigation().also {it.open(AndroidDestination.PLANNER)}
        compose.setContent {app(f,nav,p)}
        compose.onNodeWithTag("profile-edit-name").performTextReplacement("Unsaved native C")
        back();if(!p.discardEditRequested) back()
        compose.onNodeWithText("Keep editing",substring=false).performClick()
        compose.runOnIdle {check(p.profileEditor!!.draft.name=="Unsaved native C");check(nav.current==AndroidDestination.PLANNER)}
        runBlocking {f.profileSettings.save(f.configured.copy(name="Concurrent B"),f.configured)}
        val count=runBlocking {f.base.journal.timeline().size}
        compose.onNodeWithTag("profile-edit-save").performClick()
        compose.waitUntil(10000) {p.profileResult==PlanningProfileSettingsResult.Stale && !p.busy}
        compose.onNodeWithTag("profile-reload").performScrollTo().performClick()
        compose.onNodeWithText("Discard draft and reload",substring=false).performClick()
        compose.waitUntil(10000) {p.profileEditor!!.draft.name=="Concurrent B" && !p.busy}
        compose.onNodeWithText("Cancel editing",substring=false).performClick()
        compose.runOnIdle {check(p.profileEditor==null);check(nav.current==AndroidDestination.PLANNER)}
        runBlocking {check(f.base.journal.timeline().size==count)}
    }
    @Test fun nativeConfiguredProfileEditFromPlannerCommitsAndRefreshesSelection() = fixture {f,_ ->
        val p=f.workspace();val count=runBlocking {f.base.journal.timeline().size}
        compose.setContent {app(f,AndroidNavigation().also {it.open(AndroidDestination.PLANNER)},p)}
        compose.waitUntil(10000) {p.loaded};compose.onNodeWithText("Research hours",substring=false).performClick()
        compose.onNodeWithTag("profile-edit-open").performScrollTo().performClick()
        compose.onNodeWithTag("profile-edit-name").performTextReplacement("Native edited profile")
        compose.onNodeWithTag("profile-edit-save").performClick()
        compose.waitUntil(10000) {p.profileEditor==null && !p.busy}
        compose.runOnIdle {check(p.profiles.single {it.id==f.configured.id}==p.lastCommittedProfile);check(p.draft.profileId==f.configured.id)}
        runBlocking {check(f.base.journal.timeline().size==count+1);check(f.base.profiles.get(f.configured.id)!!.name=="Native edited profile")}
    }
    @Test fun actualAndroidPlanningProfileScreenshotCandidates() = fixture {f,context ->
        val p=f.workspace();runBlocking {p.refresh();p.draft=f.request()}
        val nav=AndroidNavigation().also {it.open(AndroidDestination.PLANNER)}
        var dark by mutableStateOf(false)
        compose.setContent {key(dark) {app(f,nav,p,dark=dark)}}
        val config=context.resources.configuration
        val out=File(context.getExternalFilesDir(null),"d10-03-screenshots").also {it.mkdirs()}
        for(theme in listOf(false,true)) for(case in listOf("profile-editor","profile-policy","profile-configured")) {
            compose.runOnIdle {
                p.discardProfileDraft();dark=theme
                if(case!="profile-configured") p.openProfileEditor(f.configured)
            }
            compose.waitForIdle()
            if(case=="profile-policy") compose.onNodeWithText("Block the whole local day",substring=false).performScrollTo()
            val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync()
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(out,"android-${config.screenWidthDp}x${config.screenHeightDp}-font${(config.fontScale*100).toInt()}-${if(theme) "dark" else "light"}-$case.png").outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
            check(f.base.providerRequests==0)
        }
    }
    @Test fun actualAndroidD10ProductScreenshotCandidates() = fixture {f,context ->
        val p=f.workspace();val h=f.history();val s=f.sync();val nav=AndroidNavigation()
        runBlocking {p.refresh();p.draft=f.request();h.refresh();f.enroll();f.profileConflict();s.refresh()}
        val security=androidSecurityWorkflows(f.db,dev.agenticscheduler.fixtures.D10SecurityFixtureStore(),f.base.client,f.base.ids,
            dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration(dev.agenticscheduler.sync.AccountId("d10-account"),"https://synthetic.invalid"),{}).copy(activeDeviceIds={listOf(dev.agenticscheduler.sync.DeviceId("d10-device"),dev.agenticscheduler.sync.DeviceId("synthetic-other-device"))})
        var dark by mutableStateOf(false)
        var stopped by mutableStateOf<String?>(null)
        compose.setContent {key(dark) {app(f,nav,p,h,s,dark,stopped=stopped,security=security)}}
        val config=context.resources.configuration
        val cases=when {
            config.fontScale>1.5f -> listOf("planner-preview","history-detail","sync-conflict")
            config.screenWidthDp==360 -> listOf("planner-input","planner-preview","history-detail","history-timeline","sync-conflict","sync-stopped","settings","revoke-confirmation")
            config.screenWidthDp==840 || config.screenWidthDp==1024 -> listOf("planner-preview","history-detail","sync-conflict","settings")
            else -> listOf("planner-preview","settings")
        }
        val out=File(context.getExternalFilesDir(null),"d10-03-screenshots").also {it.mkdirs()}
        for(theme in listOf(false,true)) for(case in cases) {
            compose.waitForIdle()
            compose.waitUntil(10000) {!p.busy && !h.busy && !s.busy}
            runBlocking {
                p.cancelPreview();h.closeDetail();s.closeDetail()
                if(case=="planner-preview") p.requestPreview()
                if(case=="history-detail") h.select(h.rows.first().operation.mutationId)
                if(case=="sync-conflict") s.inspect("d10-profile-conflict")
            }
            compose.runOnIdle {dark=theme;stopped=if(case=="sync-stopped") "HTTP_401" else null;nav.open(when {case.startsWith("planner")->AndroidDestination.PLANNER;case.startsWith("history")->AndroidDestination.HISTORY;case.startsWith("sync") || case=="revoke-confirmation"->AndroidDestination.SYNC_SECURITY;else->AndroidDestination.SETTINGS})}
            val tag=when {case=="planner-input"->"planner-workspace";case.startsWith("planner")->"planner-preview";case=="history-timeline"->"history-timeline";case.startsWith("history")->"history-detail";case=="sync-conflict"->"business-conflict-detail";case=="sync-stopped" || case=="revoke-confirmation"->"sync-security";else->"settings-hub"}
            try {compose.waitUntil(10000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
            catch (failure:AssertionError) {throw AssertionError("Capture $case / dark=$theme: preview=${p.preview}, busy=${p.busy}, result=${p.message}",failure)}
            compose.waitForIdle()
            if(case=="revoke-confirmation") {
                compose.onNodeWithTag("sync-security").performScrollToNode(hasText("Load active devices",substring=false))
                compose.onNodeWithText("Load active devices",substring=false).performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithText("Revoke device synthetic-other-device",substring=false).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Revoke device synthetic-other-device",substring=false).performScrollTo().performClick()
                compose.onNodeWithText("Confirm device revocation",substring=false).assertExists()
            }
            val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync()
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(out,"android-${config.screenWidthDp}x${config.screenHeightDp}-font${(config.fontScale*100).toInt()}-${if(theme) "dark" else "light"}-$case.png").outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
            if(case=="revoke-confirmation") compose.onNodeWithText("Cancel security action",substring=false).performClick()
            check(f.base.providerRequests==0)
        }
    }
    @Composable private fun app(f:D10ProductFixtureGraph,nav:AndroidNavigation,p:PlannerWorkspaceCoordinator=remember {f.workspace()},
        history:HistoryScreenCoordinator=remember {f.history()},sync:SyncSecurityScreenCoordinator=remember {f.sync()},dark:Boolean=false,stopped:String?=null,retry:()->Unit={},security:SecurityWorkflowServices?=null) {
        val b=f.base
        AndroidApp(f.reads,f.planner,f.profileSettings,b.eventEditor,b.taskEditor,b.agent,b.run,b.secrets,b.enrollments,b.ids,b.conversationSettings,stopped,retry,
            remember {AndroidScheduleScreenCoordinator(f.reads,b.date,b.zone)},nav,dark,historyQueries=f.queries,undoService=f.undo,conflictQueries=f.conflicts,
            plannerWorkspaceSession=p,historySession=history,syncSession=sync,presentationNow=f.now,securityServices=security)
    }
    private fun back() {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle()}
    private fun fixture(test:(D10ProductFixtureGraph,Context)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val file=File.createTempFile("d10-product-",".db",context.cacheDir)
        val isolated=object:ContextWrapper(context) {override fun getDatabasePath(name:String)=file}
        val db=openAndroidDatabase(isolated);val f=D10ProductFixtureGraph(db)
        try {runBlocking {f.seed()};test(f,context)} finally {f.base.client.close();db.close();file.delete()}
    }
}
