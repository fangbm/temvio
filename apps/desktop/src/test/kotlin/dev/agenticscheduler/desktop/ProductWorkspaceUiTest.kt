package dev.agenticscheduler.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asSkiaBitmap
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.fixtures.D10ProductFixtureGraph
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.presentation.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ProductWorkspaceUiTest {
    @Test fun explicitPlannerPreviewApplyCommitsThroughMountedProductScreen() = fixture {f ->
        val c=f.workspace();val nav=DesktopNavigation().also {it.open(DesktopDestination.PLANNER)}
        val count=runBlocking {f.base.journal.timeline().size}
        runDesktopComposeUiTest(width=1280,height=1000) {
            setContent {app(f,nav,c)}
            waitUntil(timeoutMillis=10000) {c.loaded}
            onNodeWithText("Research hours",substring=false).performClick()
            onNodeWithTag("planner-reference").performTextReplacement(f.now.toString())
            onNodeWithTag("planner-horizon-start").performTextReplacement(f.now.toString())
            onNodeWithTag("planner-horizon-end").performTextReplacement("2026-10-06T17:00:00Z")
            onNodeWithTag("planner-request-preview").performScrollTo().performClick()
            waitUntil(timeoutMillis=10000) {c.preview is PlannerPreview.Applicable && !c.busy}
            onNodeWithText("Proposed changes",substring=false).assertExists()
            runBlocking {check(f.base.journal.timeline().size==count)}
            onNodeWithTag("planner-apply-time").performScrollTo().performTextReplacement(f.now.toString())
            onNodeWithTag("planner-apply").performScrollTo().performClick()
            waitUntil(timeoutMillis=10000) {c.preview==null && !c.busy}
            runBlocking {check(f.base.journal.timeline().size==count+1)};check(f.base.providerRequests==0)
        }
    }
    @Test fun settingsSharesProfilesDirtyCancelAndSessionThemeWithoutBusinessWrites() = fixture {f ->
        val c=f.workspace();val nav=DesktopNavigation().also {it.open(DesktopDestination.SETTINGS)}
        val count=runBlocking {f.base.journal.timeline().size}
        runDesktopComposeUiTest(width=1280,height=1000) {
            setContent {app(f,nav,c)}
            onNodeWithText("Use Dark theme").performClick();onNodeWithText("Use Light theme").assertExists()
            onNodeWithText("Open planning profiles").performClick();waitUntil(timeoutMillis=10000) {c.loaded}
            onNodeWithText("New Unconfigured profile").performClick()
            onNodeWithTag("profile-name").performTextInput("Unsaved profile")
            onNodeWithText("Cancel",substring=false).performClick();onNodeWithText("Keep editing").performClick()
            onNodeWithText("Unsaved profile",substring=false).assertExists()
            onNodeWithText("Cancel",substring=false).performClick();onNodeWithText("Discard draft").performClick()
            runBlocking {check(f.base.journal.timeline().size==count)};check(f.base.providerRequests==0)
        }
    }
    @Test fun themeNavigationAndProviderLinkOutNeverApplySessionPlannerOrCopyCredentials() = fixture {f ->
        val p=f.workspace();runBlocking {p.refresh();p.draft=f.request();p.requestPreview()}
        val branch=(p.preview as PlannerPreview.Applicable).branch
        val nav=DesktopNavigation().also {it.open(DesktopDestination.PLANNER)}
        val count=runBlocking {f.base.journal.timeline().size}
        runDesktopComposeUiTest(width=1280,height=1000) {
            setContent {app(f,nav,p)}
            waitUntil(timeoutMillis=10000) {onAllNodesWithTag("planner-preview").fetchSemanticsNodes().isNotEmpty()}
            runOnIdle {nav.open(DesktopDestination.SETTINGS)}
            onNodeWithText("Use Dark theme").performClick()
            onNodeWithText("Open Provider settings").performScrollTo().performClick()
            runOnIdle {check(nav.current==DesktopDestination.AGENT);check((p.preview as PlannerPreview.Applicable).branch==branch)}
            runBlocking {check(f.base.journal.timeline().size==count);check(f.base.tasks.observeFocusBlocks().first().isEmpty())}
            check(f.base.providerRequests==0)
        }
    }
    @Test fun historyDetailShowsUnsupportedAndTypedDiffWithoutAutomaticUndo() = fixture {f ->
        val h=f.history();runBlocking {h.refresh();h.select(h.rows.first().operation.mutationId)}
        val count=runBlocking {f.base.journal.timeline().size}
        runDesktopComposeUiTest(width=1280,height=1000) {
            setContent {app(f,DesktopNavigation().also {it.open(DesktopDestination.HISTORY)},history=h)}
            waitUntil(timeoutMillis=10000) {onAllNodesWithTag("history-detail").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithText("Before: absent",substring=true).assertExists()
            onNodeWithText("Undo unsupported ·",substring=true).performScrollTo().assertExists()
            onNodeWithTag("history-undo").assertDoesNotExist()
            onNodeWithText("Close mutation detail").performScrollTo().performClick()
            runOnIdle {check(h.detail==null)}
            runBlocking {check(f.base.journal.timeline().size==count)}
        }
    }
    @Test fun syncStoppedRetryIsExplicitAndOpenConflictRemainsReadOnly() = fixture {f ->
        runBlocking {f.enroll();f.profileConflict()};val s=f.sync();var retries=0
        val count=runBlocking {f.base.journal.timeline().size}
        runDesktopComposeUiTest(width=1280,height=1000) {
            setContent {app(f,DesktopNavigation().also {it.open(DesktopDestination.SETTINGS)},sync=s,stopped="HTTP_401",retry={retries++})}
            onNodeWithText("Open Sync / Security").performScrollTo().performClick();waitUntil(timeoutMillis=10000) {s.loaded}
            onNodeWithText("Automatic sync stopped",substring=false).assertExists();onNodeWithTag("sync-explicit-retry").performClick()
            runOnIdle {check(retries==1)}
            onNodeWithTag("sync-security").performScrollToNode(hasText("Inspect business conflict",substring=false))
            onNodeWithText("Inspect business conflict").performScrollTo().performClick()
            waitUntil(timeoutMillis=10000) {s.selected!=null};onNodeWithText("Resolution unavailable",substring=false).performScrollTo().assertExists()
            runBlocking {check(f.conflicts.listOpen(f.space).size==1);check(f.base.journal.timeline().size==count)}
            check(f.base.providerRequests==0)
        }
    }
    @Test fun deferredHistoryPagesRemainValidAcrossGrowthRefreshAndScrolledMeasurement() = fixture {f ->
        runBlocking {repeat(65) {f.profileSettings.createUnconfigured("Bounded history fixture $it")}}
        val h=f.history();val nav=DesktopNavigation().also {it.open(DesktopDestination.HISTORY)}
        runDesktopComposeUiTest(width=1280,height=900) {
            setContent {app(f,nav,history=h)}
            waitUntil(timeoutMillis=10000) {h.loaded}
            repeat(5) {
                onNodeWithTag("history-timeline").performScrollToNode(hasText("Load more History",substring=false))
                onNodeWithText("Load more History",substring=false).performClick()
                waitUntil(timeoutMillis=10000) {h.rows.size>50 && !h.busy};waitForIdle()
                onNodeWithTag("history-timeline").performScrollToNode(hasText("Refresh History",substring=false))
                onNodeWithText("Refresh History",substring=false).performClick()
                waitUntil(timeoutMillis=10000) {h.rows.size==50 && !h.busy};waitForIdle()
            }
            check(f.base.providerRequests==0)
        }
    }
    @Test fun hostUiScopeSurvivesChildRemountAndBusyApplyDoesNotCommitTwice() = fixture {f ->
        val refreshEntered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var blockRefresh=false
        val tasks=object:dev.agenticscheduler.application.persistence.TaskRepository by f.base.tasks {
            override fun observeTasks()=flow {
                if(blockRefresh) {refreshEntered.complete(Unit);release.await()}
                emitAll(f.base.tasks.observeTasks())
            }
        }
        val reads=dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService(f.base.events,tasks,f.base.profiles,f.base.academics,f.source)
        val p=PlannerWorkspaceCoordinator(reads,f.planner,f.profileSettings)
        runBlocking {p.refresh();p.draft=f.request();p.requestPreview()}
        val count=runBlocking {f.base.journal.timeline().size}
        var mounted by mutableStateOf(true);lateinit var host:CoroutineScope
        runDesktopComposeUiTest(width=1280,height=1000) {
            setContent {
                host=rememberCoroutineScope() // Same root-owned UI scope as production Desktop composition.
                if(mounted) app(f,DesktopNavigation().also {it.open(DesktopDestination.PLANNER)},p,hostScope=host)
            }
            onNodeWithTag("planner-apply-time").performScrollTo().performTextReplacement(f.now.toString())
            runOnIdle {blockRefresh=true}
            onNodeWithTag("planner-apply").performScrollTo().performClick()
            waitUntil(timeoutMillis=10000) {refreshEntered.isCompleted}
            runOnIdle {
                check(p.busy);check(p.preview==null);mounted=false
                host.launch {p.apply(f.now)} // Guard remains serialized on the presentation dispatcher.
            }
            waitForIdle();runBlocking {check(f.base.journal.timeline().size==count+1)}
            runOnIdle {release.complete(Unit)}
            waitUntil(timeoutMillis=10000) {!p.busy}
            runOnIdle {check(p.message!!.startsWith("Applied"));mounted=true}
            waitForIdle();runBlocking {check(f.base.journal.timeline().size==count+1)}
        }
    }
    @Test fun actualDesktopD10ProductScreenshotCandidates() {
        val out=File("build/d10-03/screenshots").also {it.mkdirs()}
        val variants=listOf(Triple(640,720,1f),Triple(1024,900,1f),Triple(1280,1000,1f),Triple(1440,1000,1f),Triple(1920,1080,1f),Triple(1280,1000,2f))
        for((width,height,font) in variants) for(dark in listOf(false,true)) {
            val cases=when(width) {1280 -> if(font>1.5f) listOf("planner-preview","history-detail","sync-conflict") else listOf("planner-input","planner-preview","planner-stale","history-detail","history-timeline","sync-conflict","sync-stopped","settings","revoke-confirmation")
                640 -> listOf("planner-input","planner-preview","history-detail","sync-conflict","settings")
                1920 -> listOf("planner-preview","history-detail","sync-conflict","settings")
                else -> listOf("planner-preview","settings")}
            for(case in cases) fixture {f ->
                val p=f.workspace();val h=f.history();val s=f.sync()
                runBlocking {
                    p.refresh();p.draft=f.request()
                    if(case.startsWith("planner") && case!="planner-input") p.requestPreview()
                    if(case=="planner-stale") {f.profileSettings.save(f.configured.copy(name="Changed profile fact"));p.apply(f.now)}
                    h.refresh();if(case=="history-detail") h.select(h.rows.first().operation.mutationId)
                    if(case=="sync-conflict" || case=="revoke-confirmation") {f.enroll();val conflict=f.profileConflict();s.refresh();if(case=="sync-conflict") s.inspect(conflict.conflictId)}
                }
                val security=if(case=="revoke-confirmation") desktopSecurityWorkflows(f.db,dev.agenticscheduler.fixtures.D10SecurityFixtureStore(),f.base.client,f.base.ids,
                    dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration(dev.agenticscheduler.sync.AccountId("d10-account"),"https://synthetic.invalid"),{}).copy(activeDeviceIds={listOf(dev.agenticscheduler.sync.DeviceId("d10-device"),dev.agenticscheduler.sync.DeviceId("synthetic-other-device"))}) else null
                val nav=DesktopNavigation().also {it.open(when {case.startsWith("planner")->DesktopDestination.PLANNER;case.startsWith("history")->DesktopDestination.HISTORY;else->DesktopDestination.SETTINGS})}
                runDesktopComposeUiTest(width=width,height=height) {
                    setContent {CompositionLocalProvider(LocalDensity provides Density(1f,font)) {app(f,nav,p,h,s,dark,stopped=if(case=="sync-stopped") "HTTP_401" else null,security=security)}}
                    if(case in listOf("sync-conflict","sync-stopped","revoke-confirmation")) onNodeWithText("Open Sync / Security").performScrollTo().performClick()
                    if(case=="revoke-confirmation") {
                        onNodeWithTag("sync-security").performScrollToNode(hasText("Load active devices",substring=false))
                        onNodeWithText("Load active devices",substring=false).performClick()
                        waitUntil(timeoutMillis=10000) {onAllNodesWithText("Revoke device synthetic-other-device",substring=false).fetchSemanticsNodes().isNotEmpty()}
                        onNodeWithText("Revoke device synthetic-other-device",substring=false).performScrollTo().performClick()
                    }
                    if(case.startsWith("planner") && case!="planner-input") waitUntil(timeoutMillis=10000) {onAllNodesWithTag("planner-preview").fetchSemanticsNodes().isNotEmpty()}
                    if(case=="history-detail") waitUntil(timeoutMillis=10000) {onAllNodesWithTag("history-detail").fetchSemanticsNodes().isNotEmpty()}
                    if(case=="sync-conflict") waitUntil(timeoutMillis=10000) {onAllNodesWithTag("business-conflict-detail").fetchSemanticsNodes().isNotEmpty()}
                    waitForIdle()
                    val dialog=case=="revoke-confirmation" || case in listOf("planner-preview","planner-stale","history-detail","sync-conflict") && (width<1024 || font>1.5f)
                    val target=if(dialog) onAllNodes(isRoot()).onLast() else onAllNodes(isRoot()).onFirst()
                    val file=File(out,"desktop-${width}x$height-font${(font*100).toInt()}-${if(dark) "dark" else "light"}-$case.png")
                    Image.makeFromBitmap(target.captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.use {file.writeBytes(it.bytes)}
                    check(f.base.providerRequests==0)
                }
            }
        }
    }
    @Composable private fun app(f:D10ProductFixtureGraph,nav:DesktopNavigation,p:PlannerWorkspaceCoordinator=remember {f.workspace()},
        history:HistoryScreenCoordinator=remember {f.history()},sync:SyncSecurityScreenCoordinator=remember {f.sync()},dark:Boolean=false,stopped:String?=null,retry:()->Unit={},security:SecurityWorkflowServices?=null,hostScope:CoroutineScope?=null) {
        val b=f.base
        DesktopApp(f.reads,f.planner,f.profileSettings,b.eventEditor,b.taskEditor,stopped,retry,b.run,b.agent,b.secrets,b.enrollments,b.ids,b.conversationSettings,
            remember {DesktopScheduleScreenCoordinator(f.reads,b.date,b.zone)},nav,dark,historyQueries=f.queries,undoService=f.undo,conflictQueries=f.conflicts,
            plannerWorkspaceSession=p,historySession=history,syncSession=sync,presentationNow=f.now,securityServices=security,applicationActionScope=hostScope)
    }
    private fun fixture(test:(D10ProductFixtureGraph)->Unit) {
        val file=File.createTempFile("d10-ui-",".db");val db=openDesktopDatabase(file.absolutePath);val f=D10ProductFixtureGraph(db)
        try {runBlocking {f.seed()};test(f)} finally {f.base.client.close();db.close();file.delete()}
    }
}
