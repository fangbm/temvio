package dev.agenticscheduler.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.fixtures.D10AgentFixtureGraph
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.*
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.jetbrains.skia.*
import java.io.File

@OptIn(ExperimentalTestApi::class)
class AgentWorkspaceUiTest {
    @Test fun rootNavigationThemeAndResizeRetainDraftWithoutSend() = fixture {f ->
        runDesktopComposeUiTest(width=1280,height=800) {
            val c=f.workspace();val nav=DesktopNavigation().also {it.open(DesktopDestination.AGENT)}
            var width by mutableStateOf(1280f)
            setContent {Box(Modifier.requiredWidth(width.dp)) {DesktopApp(f.base.reads,f.base.planner,f.base.profileSettings,f.base.eventEditor,f.base.taskEditor,null,{},
                f.run,f.base.agent,f.base.secrets,f.base.enrollments,f.base.ids,f.base.conversationSettings,
                navigationSession=nav,initialDark=false,agentWorkspaceSession=c)}}
            waitUntil(timeoutMillis=10000) {c.loaded};onNodeWithTag("agent-command").performTextInput("Never auto send\nSecond line")
            onNodeWithTag("desktop-nav-CALENDAR").performClick();onNodeWithTag("theme-toggle").performClick();onNodeWithTag("global-agent").performClick()
            runOnIdle {width=640f};onNodeWithText("Never auto send\nSecond line").assertExists()
            runOnIdle {width=1920f};check(f.requests==0);check(c.messages.size==2)
        }
    }
    @Test fun restoredConfirmationReviewLaterAndRemountNeverExecute() = fixture {f ->
        val c=f.workspace();runBlocking {f.prepareScene("confirmation",c)};val call=c.pending!!;val before=f.requests
        runDesktopComposeUiTest(width=1024,height=768) {
            var mounted by mutableStateOf(true);var dark by mutableStateOf(false)
            setContent {TemvioTheme(dark) {if(mounted) AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}}
            onNodeWithTag("agent-confirm").assertExists();onNodeWithText("Review later").performClick()
            runOnIdle {mounted=false};runOnIdle {dark=true;mounted=true}
            waitUntil(timeoutMillis=10000) {c.overlay==AgentOverlay.CONFIRMATION}
            onNodeWithTag("agent-confirm").assertExists();check(c.pending==call);check(f.requests==before)
        }
    }
    @Test fun explicitUiConfirmRecordsActualResultAndMutation() = fixture {f ->
        val c=f.workspace();runBlocking {f.prepareScene("confirmation",c)};val call=c.pending!!
        runDesktopComposeUiTest(width=1280,height=800) {
            setContent {TemvioTheme(false) {AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}}
            waitForIdle();waitUntil(timeoutMillis=10000) {c.loaded && !c.busy}
            mainClock.advanceTimeByFrame();waitForIdle()
            waitUntil(timeoutMillis=10000) {onAllNodes(hasTestTag("agent-confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            onNodeWithTag("agent-confirm").assertIsEnabled().performClick();waitUntil(timeoutMillis=10000) {!c.busy && c.pending==null}
            check(c.results.single().callId==call.id);check(c.results.single().mutationIds.size==1)
            onNodeWithTag("agent-conversation").performScrollToNode(hasText("Result · Success"))
            onNodeWithText("Committed · 1 recorded business mutation(s)").assertExists()
        }
    }
    @Test fun explicitUiDenyLeavesBusinessFactsUnchanged() = fixture {f ->
        val c=f.workspace();runBlocking {f.prepareScene("confirmation",c)};val before=runBlocking {f.base.journal.timeline().size}
        runDesktopComposeUiTest(width=640,height=720) {
            setContent {TemvioTheme(true) {AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}}
            waitForIdle();waitUntil(timeoutMillis=10000) {c.loaded && !c.busy}
            mainClock.advanceTimeByFrame();waitForIdle()
            waitUntil(timeoutMillis=10000) {onAllNodes(hasTestTag("agent-deny") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            onNodeWithTag("agent-deny").assertIsEnabled().performClick();waitUntil(timeoutMillis=10000) {!c.busy && c.pending==null}
            check(c.results.single().status==AgentToolResultStatus.PERMISSION_DENIED)
            check(runBlocking {f.base.journal.timeline().size}==before)
        }
    }
    @Test fun chatOnlyHasUsableSendAndNoProviderConfigurationFieldsInConversation() = fixture {f ->
        val c=f.workspace();runBlocking {f.prepareScene("chat-only",c)}
        runDesktopComposeUiTest(width=800,height=600) {
            setContent {TemvioTheme(false) {AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}}
            onNodeWithText("Chat only").assertExists();onNodeWithText("API credential (optional)").assertDoesNotExist()
            onNodeWithTag("agent-command").performTextInput("Create an event");onNodeWithTag("agent-send").assertIsEnabled().assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=10000) {!c.busy && c.command.isEmpty()};check(f.lastToolCount==0);check(c.calls.isEmpty())
        }
    }
    @Test fun localPermissionEditorUsesOnlyExistingPolicyChoices() = fixture {f ->
        val c=f.workspace()
        runDesktopComposeUiTest(width=1024,height=768) {
            setContent {TemvioTheme(false) {AgentPermissionSettings(c,rememberCoroutineScope())}}
            waitUntil(timeoutMillis=10000) {c.loaded}
            onNodeWithTag("agent-permissions").performScrollToNode(hasTestTag("permission-LOW_RISK_CREATE-DENY"))
            onNodeWithTag("permission-LOW_RISK_CREATE-DENY").performClick()
            waitUntil(timeoutMillis=10000) {c.policy?.modeFor(AgentToolCapability.LOW_RISK_CREATE)==AgentPermissionMode.DENY && !c.busy}
            onNodeWithTag("agent-permissions").performScrollToNode(hasText("Destructive"))
            onNodeWithTag("permission-DESTRUCTIVE-ALLOW_DIRECT").assertDoesNotExist();check(f.requests==0)
        }
    }
    @Test fun twoHundredPercentPreviewActionsRemainReachableAndNoResizeWrite() = fixture {f ->
        val c=f.workspace();runBlocking {f.prepareScene("confirmation",c)};val before=f.requests
        runDesktopComposeUiTest(width=640,height=720) {
            setContent {CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {TemvioTheme(true) {AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}}}
            onNodeWithTag("agent-confirm").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
            onNodeWithTag("agent-deny").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
            onNodeWithText("Technical details").performScrollTo().assertExists()
            check(c.pending!=null);check(f.requests==before)
        }
    }

    @Test fun actualDesktopD10AgentScreenshotCandidates() {
        val out=File("build/d10-04/screenshots").also {it.mkdirs()}
        val variants=listOf(Triple(640,720,1f),Triple(800,600,1f),Triple(1024,768,1f),Triple(1280,800,1f),Triple(1440,900,1f),Triple(1920,1080,1f),Triple(640,720,2f),Triple(800,360,1f))
        for((width,height,font) in variants) {
            val scenes=when {
                width==1280 -> listOf("conversation","tool-result","confirmation","stale","denied","chat-only","threads","permissions","provider-status")
                font==2f -> listOf("conversation","confirmation","permissions")
                height<480 -> listOf("conversation","confirmation")
                else -> listOf("conversation")
            }
            for(scene in scenes) for(dark in listOf(false,true)) fixture {f ->
                val c=f.workspace();runBlocking {f.prepareScene(scene,c)};val before=f.requests
                runDesktopComposeUiTest(width=width,height=height) {
                    val nav=DesktopNavigation().also {it.open(if(scene=="permissions") DesktopDestination.SETTINGS else DesktopDestination.AGENT)}
                    setContent {CompositionLocalProvider(LocalDensity provides Density(1f,font)) {TemvioTheme(dark) {
                        DesktopAppShell(nav,dark,{}) {if(scene=="permissions") AgentPermissionSettings(c,rememberCoroutineScope()) else AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}
                    }}}
                    waitUntil(timeoutMillis=10000) {c.loaded && !c.busy};mainClock.advanceTimeByFrame();waitForIdle()
                    if(scene in listOf("tool-result","stale","denied")) onNodeWithTag("agent-conversation").performScrollToNode(hasTestTag("agent-result-status"))
                    if(scene=="conversation") onNodeWithTag("agent-conversation").performScrollToNode(hasText(c.messages.last().content))
                    if(height<480 && scene=="conversation") {
                        onNodeWithTag("agent-command").performScrollTo().assertIsDisplayed()
                        onNodeWithTag("agent-send").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
                    }
                    val file=File(out,"desktop-${width}x$height-font${(font*100).toInt()}-${if(dark) "dark" else "light"}-$scene.png")
                    val target=if(scene in listOf("confirmation","threads")) onAllNodes(isRoot()).onLast() else onAllNodes(isRoot()).onFirst()
                    Image.makeFromBitmap(target.captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.use {file.writeBytes(it.bytes)}
                    check(f.requests==before)
                }
            }
        }
    }
    private fun fixture(test:(D10AgentFixtureGraph)->Unit) {
        val file=File.createTempFile("d10-agent-ui-", ".db");val db=openDesktopDatabase(file.absolutePath);val f=D10AgentFixtureGraph(db)
        try {runBlocking {f.seed()};test(f)} finally {f.close();db.close();file.delete()}
    }
}
