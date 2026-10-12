package dev.agenticscheduler.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.context.*
import dev.agenticscheduler.domain.id.TaskId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.agenticscheduler.agent.permission.*
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.fixtures.D10AgentFixtureGraph
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

class AgentWorkspaceInstrumentedTest {
    @get:Rule val compose=createComposeRule()

    @Test fun explicitChatNoAnchorPersistsCorrectUserAndNeverRunsOnNavigation() = fixture {f, _ ->
        val c=f.workspace();val nav=AndroidNavigation().also {it.open(AndroidDestination.AGENT)}
        mount(f,c,nav)
        compose.waitUntil(15000) {c.loaded}
        compose.onNodeWithTag("agent-command").performTextInput("Synthetic explicit command")
        compose.onNodeWithTag("android-nav-CALENDAR").performClick();compose.onNodeWithTag("theme-toggle").performClick();compose.onNodeWithTag("android-nav-AGENT").performClick()
        compose.onNodeWithText("Synthetic explicit command").assertExists();check(f.requests==0)
        compose.onNodeWithTag("agent-send").assertIsDisplayed().performClick()
        compose.waitUntil(15000) {!c.busy && c.command.isEmpty()}
        check(c.messages.any {it.role==AgentMessageRole.USER && it.content=="Synthetic explicit command"});check(f.requests==1)
    }
    @Test fun contextualTaskOpenRetainsVisibleAttachmentAndSendsExactUserOnlyExplicitly() = fixture {f, _ ->
        val c=f.workspace();val nav=AndroidNavigation().also {it.open(AndroidDestination.TASKS)}
        val core=CoreScreenCoordinator();mount(f,c,nav,core)
        compose.waitUntil(15000) {compose.onAllNodesWithText("View Task").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("View Task").performClick()
        // A compact detail is a separate Dialog window; wait for its semantics
        // before looking up the contextual action in that window.
        compose.waitUntil(15000) {compose.onAllNodesWithTag("agent-discuss-task").fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("agent-discuss-task").performScrollTo().performClick()
        compose.waitUntil(15000) {c.loaded && nav.current==AndroidDestination.AGENT}
        val context=checkNotNull(c.attachedContext);check(context.anchor is AgentContextAnchor.Task);check(f.requests==0);check(c.messages.size==2)
        compose.onNodeWithTag("agent-command").performTextInput("Exact contextual Android command")
        compose.onNodeWithTag("theme-toggle").performClick();compose.onNodeWithTag("android-nav-CALENDAR").performClick();compose.onNodeWithTag("android-nav-AGENT").performClick()
        compose.onNodeWithTag("agent-context-label").assertIsDisplayed();check(c.attachedContext==context);check(f.requests==0)
        compose.onNodeWithTag("agent-send").assertIsDisplayed().performClick()
        compose.waitUntil(15000) {!c.busy && c.command.isEmpty()}
        check(c.messages.last {it.role==AgentMessageRole.USER}.content=="Exact contextual Android command")
        check(f.bodies.single().contains(context.anchor.renderForModel().replace("\"","\\\"")))
        check(!f.bodies.single().contains(context.label));check(c.attachedContext==context)
        compose.onNodeWithTag("agent-clear-context").performClick();check(c.attachedContext==null)
        compose.onNodeWithTag("android-nav-TASKS").performClick();compose.onNodeWithText("View Task").performClick()
        // Return from detail before choosing the ordinary primary Agent destination.
        compose.waitForIdle();compose.waitUntil(15000) {core.selection is CoreSelection.TaskDetail}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.onNodeWithText("Selected detail").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15000) {core.selection==null}
        compose.onNodeWithTag("android-nav-AGENT").performClick();compose.waitUntil(15000) {c.loaded}
        check(c.attachedContext==null);check(f.requests==1)
    }
    @Test fun nativeTwoHundredPercentContextClearIsReachableWithoutSend() = fixture {f, _ ->
        val c=f.workspace();val attachment=taskAgentContext(TaskId("00000000-0000-7000-8000-000000008801"),"A long synthetic selected Task")
        c.attachContext(attachment)
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
            TemvioTheme(true) {AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}
        }}
        compose.waitUntil(15000) {c.loaded};compose.onNodeWithTag("agent-command").performTextInput("Unsent draft")
        compose.onNodeWithTag("agent-clear-context").assertHeightIsAtLeast(48.dp).assertIsDisplayed().performClick()
        check(c.attachedContext==null);check(c.command=="Unsent draft");check(f.requests==0)
        compose.onNodeWithTag("agent-send").assertIsDisplayed()
    }
    @Test fun nativeBackClosesExactRestoredConfirmationWithoutDenyOrWrite() = fixture {f, _ ->
        val c=f.workspace();runBlocking {f.prepareScene("confirmation",c)};val call=c.pending!!;val before=f.requests
        mount(f,c,AndroidNavigation().also {it.open(AndroidDestination.AGENT)})
        compose.waitForIdle();compose.waitUntil(15000) {c.loaded && !c.busy}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.onNodeWithTag("agent-confirm").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15000) {c.overlay==null};check(c.pending==call);check(f.requests==before)
        compose.onNodeWithText("Review pending action").performScrollTo().performClick();compose.onNodeWithTag("agent-deny").performClick()
        compose.waitUntil(15000) {!c.busy && c.pending==null};check(c.results.single().status==AgentToolResultStatus.PERMISSION_DENIED)
        check(c.results.single().mutationIds.isEmpty())
    }
    @Test fun explicitConfirmUsesPersistedIdentityAndOnlyOneCommit() = fixture {f, _ ->
        val c=f.workspace();runBlocking {f.prepareScene("confirmation",c)};val call=c.pending!!
        mount(f,c,AndroidNavigation().also {it.open(AndroidDestination.AGENT)})
        compose.waitForIdle();compose.waitUntil(15000) {c.loaded && !c.busy}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.onNodeWithTag("agent-confirm").assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        compose.waitUntil(15000) {!c.busy && c.pending==null};check(c.results.single().callId==call.id)
        check(c.results.single().status==AgentToolResultStatus.SUCCESS);check(c.results.single().mutationIds.size==1)
        runBlocking {c.confirm(call.id,true)};check(c.results.size==1)
    }
    @Test fun providerSettingsIsSeparateAndNeverCreatesThreadOrSends() = fixture {f, _ ->
        val c=f.workspace();val nav=AndroidNavigation().also {it.open(AndroidDestination.AGENT)}
        mount(f,c,nav);compose.waitUntil(15000) {c.loaded};val messages=c.messages;val threads=c.threads
        compose.onNodeWithTag("agent-conversation").performScrollToNode(hasText("Provider settings"));compose.onNodeWithText("Provider settings").performClick()
        compose.waitUntil(15000) {nav.current==AndroidDestination.PROVIDER}
        compose.onNodeWithText("Provider base URL (explicit)").assertExists();compose.onNodeWithTag("agent-command").assertDoesNotExist()
        compose.onNodeWithTag("android-nav-AGENT").performClick();compose.waitUntil(15000) {c.loaded}
        check(c.messages==messages);check(c.threads==threads);check(f.requests==0)
    }
    @Test fun chatOnlySendUsesZeroSchemasAndNoAction() = fixture {f, _ ->
        val c=f.workspace();runBlocking {f.prepareScene("chat-only",c)}
        mount(f,c,AndroidNavigation().also {it.open(AndroidDestination.AGENT)})
        compose.waitForIdle();compose.waitUntil(15000) {c.loaded && !c.busy}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.onNodeWithText("Chat only").assertExists()
        compose.onNodeWithTag("agent-command").performTextInput("Create something")
        compose.waitUntil(15000) {c.canSend && c.command=="Create something"}
        compose.onNodeWithTag("agent-send").assertIsEnabled().assertIsDisplayed().performClick()
        try {compose.waitUntil(15000) {!c.busy && c.command.isEmpty()}}
        catch(error:ComposeTimeoutException) {throw AssertionError("Send completion missing: busy=${c.busy}, canSend=${c.canSend}, draftBlank=${c.command.isBlank()}, requests=${f.requests}, diagnostic=${c.diagnostic}",error)}
        check(f.lastToolCount==0);check(c.calls.isEmpty());check(c.actions.isEmpty())
    }
    @Test fun localPermissionEditorPersistsPolicyWithoutBusinessWriteOrSend() = fixture {f, _ ->
        val c=f.workspace();val before=runBlocking {f.base.journal.timeline().size}
        compose.setContent {TemvioTheme(false) {AgentPermissionSettings(c,rememberCoroutineScope())}}
        compose.waitUntil(15000) {c.loaded}
        compose.onNodeWithTag("agent-permissions").performScrollToNode(hasTestTag("permission-SOURCE_FACT_UPDATE-DENY"))
        compose.onNodeWithTag("permission-SOURCE_FACT_UPDATE-DENY").performClick()
        compose.waitUntil(15000) {!c.busy && c.policy?.modeFor(AgentToolCapability.SOURCE_FACT_UPDATE)==AgentPermissionMode.DENY}
        check(runBlocking {f.base.agent.permissionPolicy().modeFor(AgentToolCapability.SOURCE_FACT_UPDATE)}==AgentPermissionMode.DENY)
        check(runBlocking {f.base.journal.timeline().size}==before);check(f.requests==0)
    }

    @Test fun actualAndroidD10AgentScreenshotCandidates() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val config=context.resources.configuration
        val width=config.screenWidthDp;val height=config.screenHeightDp;val scale=(config.fontScale*100).toInt()
        val arguments=InstrumentationRegistry.getArguments()
        arguments.getString("captureWidth")?.let {check(width==it.toInt()) {"Requested width not applied: $width"}}
        arguments.getString("captureHeight")?.let {check(height==it.toInt()) {"Requested height not applied: $height"}}
        arguments.getString("captureFont")?.let {check(scale==(it.toFloat()*100).toInt()) {"Requested font scale not applied: $scale"}}
        val out=File(context.getExternalFilesDir(null),"d10-04-screenshots").also {it.mkdirs()}
        val scenes=when {
            width==360 && scale==100 -> listOf("conversation","tool-result","confirmation","stale","denied","chat-only","threads","permissions","provider-status","context-task","context-calendar","context-viewport")
            scale>=190 -> listOf("conversation","confirmation","permissions","context-task")
            height<480 -> listOf("conversation","confirmation")
            else -> listOf("conversation")
        }
        var current by mutableStateOf<AgentWorkspaceCoordinator?>(null)
        var sceneNow by mutableStateOf("conversation")
        var dark by mutableStateOf(false)
        compose.setContent {TemvioTheme(dark) {
            val c=current
            if(c!=null) {
                val nav=remember(c,sceneNow) {AndroidNavigation().also {it.open(if(sceneNow=="permissions") AndroidDestination.SETTINGS else AndroidDestination.AGENT)}}
                AndroidAppShell(nav,dark,{}) {if(sceneNow=="permissions") AgentPermissionSettings(c,rememberCoroutineScope()) else AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}
            }
        }}
        for(scene in scenes) for(theme in listOf(false,true)) fixture {f, _ ->
            val c=f.workspace();runBlocking {f.prepareScene(scene,c)};val before=f.requests
            compose.runOnIdle {sceneNow=scene;dark=theme;current=c}
            compose.waitUntil(15000) {c.loaded && !c.busy};compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
            if(scene in listOf("tool-result","stale","denied")) compose.onNodeWithTag("agent-conversation").performScrollToNode(hasTestTag("agent-result-status"))
            if(scene=="conversation") compose.onNodeWithTag("agent-conversation").performScrollToNode(hasText(c.messages.last().content))
            if(scene.startsWith("context-")) {
                compose.onNodeWithTag("agent-clear-context").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
                compose.onNodeWithTag("agent-context-label").assertIsDisplayed();compose.onNodeWithTag("agent-send").assertIsDisplayed()
            }
            if(height<480 && scene=="conversation") {
                compose.onNodeWithTag("agent-command").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("agent-send").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
            }
            if(scene=="confirmation") {compose.onNodeWithTag("agent-confirm").assertIsDisplayed();compose.onNodeWithTag("agent-deny").assertIsDisplayed()}
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(out,"android-${width}x$height-font$scale-${if(theme) "dark" else "light"}-$scene.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle();check(f.requests==before)
            compose.runOnIdle {current=null};compose.waitForIdle()
        }
    }

    private fun mount(f:D10AgentFixtureGraph,c:AgentWorkspaceCoordinator,nav:AndroidNavigation,core:CoreScreenCoordinator? = null) {
        compose.setContent {AndroidApp(f.base.reads,f.base.planner,f.base.profileSettings,f.base.eventEditor,f.base.taskEditor,
            f.base.agent,f.run,f.base.secrets,f.base.enrollments,f.base.ids,f.base.conversationSettings,null,{},
            navigationSession=nav,initialDark=false,agentWorkspaceSession=c,coreSession=core)}
    }
    private fun fixture(test:(D10AgentFixtureGraph,Context)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("d10-agent-native-", ".db",context.cacheDir)
        val wrapped=object:ContextWrapper(context) {override fun getDatabasePath(name:String)=file}
        val db=openAndroidDatabase(wrapped);val f=D10AgentFixtureGraph(db)
        try {runBlocking {f.seed()};test(f,context)} finally {f.close();db.close();file.delete()}
    }
}
