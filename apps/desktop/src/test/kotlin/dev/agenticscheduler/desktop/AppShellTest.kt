package dev.agenticscheduler.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.fixtures.D10FixtureGraph
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import androidx.compose.ui.graphics.asSkiaBitmap

@OptIn(ExperimentalTestApi::class)
class AppShellTest {
    @Test fun primarySelectionsNeverBecomeBackHistory() {
        val nav=DesktopNavigation()
        check(nav.current==DesktopDestination.TODAY)
        DesktopDestination.entries.forEach { destination ->
            nav.open(destination); nav.open(destination)
            check(nav.current==destination); check(!nav.canGoBack)
            check(!nav.back()); check(nav.current==destination)
        }
    }

    @Test fun navigationAndThemeNeverSendAndAgentDraftSurvives() = fixture { f ->
        runDesktopComposeUiTest(width=1024, height=900) {
            val nav=DesktopNavigation()
            var width by mutableStateOf(1024f)
            setContent { Box(Modifier.requiredWidth(width.dp)) { DesktopApp(f.reads,f.planner,f.profileSettings,f.eventEditor,f.taskEditor,null,{},
                f.run,f.agent,f.secrets,f.enrollments,f.ids,f.conversationSettings,
                DesktopScheduleScreenCoordinator(f.reads,f.date,f.zone),nav,false) } }
            onNodeWithTag("global-agent").performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Command").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Command").performTextInput("Do not send this draft")
            onNodeWithTag("desktop-nav-CALENDAR").performClick()
            onNodeWithTag("theme-toggle").performClick()
            onNodeWithTag("global-agent").performClick()
            runOnIdle { width=640f }
            onNodeWithText("Do not send this draft").assertExists()
            runOnIdle { width=1024f }
            onNodeWithText("Do not send this draft").assertExists()
            onNodeWithTag("desktop-nav-AGENT").assertIsSelected()
            onNodeWithText("Back").assertDoesNotExist()
            check(f.providerRequests==0)
        }
    }

    @Test fun actualComposeScreenshotCandidates() = fixture { f ->
        val variants=listOf(Triple(640,720,1f),Triple(1024,900,1f),Triple(1280,900,1f),Triple(1440,960,1f),Triple(1920,1080,1f),Triple(640,900,2f))
        val out=File("build/d10-01/screenshots").also { it.mkdirs() }
        for ((width,height,scale) in variants) for (dark in listOf(false,true)) {
            for (destination in listOf(DesktopDestination.TODAY,DesktopDestination.CALENDAR,DesktopDestination.AGENT)) {
                runDesktopComposeUiTest(width=width,height=height) {
                    val nav=DesktopNavigation().also { it.open(destination) }
                    setContent { CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {
                        DesktopApp(f.reads,f.planner,f.profileSettings,f.eventEditor,f.taskEditor,null,{},
                            f.run,f.agent,f.secrets,f.enrollments,f.ids,f.conversationSettings,
                            DesktopScheduleScreenCoordinator(f.reads,f.date,f.zone),nav,dark)
                    } }
                    waitUntil(timeoutMillis=10000) { onAllNodesWithTag(if(destination==DesktopDestination.AGENT) "agent-ready" else "schedule-ready").fetchSemanticsNodes().isNotEmpty() }
                    waitForIdle()
                    val image=onRoot().captureToImage()
                    val file=File(out,"desktop-${width}x$height-font${(scale*100).toInt()}-${if(dark) "dark" else "light"}-${destination.name.lowercase()}.png")
                    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
                    check(f.providerRequests==0)
                }
            }
        }
    }

    @Test fun explicitChatDelegatesToExistingRunAndPersistsWithoutBusinessWrites() = fixture { f ->
        runDesktopComposeUiTest(width=1024,height=1440) {
            val nav=DesktopNavigation().also { it.open(DesktopDestination.AGENT) }
            setContent { DesktopApp(f.reads,f.planner,f.profileSettings,f.eventEditor,f.taskEditor,null,{},
                f.run,f.agent,f.secrets,f.enrollments,f.ids,f.conversationSettings,
                DesktopScheduleScreenCoordinator(f.reads,f.date,f.zone),nav,false) }
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Command").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Command").performTextInput("Create an event")
            onNodeWithText("Send").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Turn completed.").fetchSemanticsNodes().isNotEmpty() }
            runBlocking {
                check(f.providerRequests==1)
                check(f.sentToolSchemas==0)
                check(f.agent.messages(f.threadId).last().content=="No change was made. Prose is not a mutation.")
                check(f.agent.toolCalls(f.threadId).isEmpty())
                check(f.agent.actions(f.threadId).isEmpty())
                check(f.journal.timeline().size==4)
            }
        }
    }

    @Test fun dirtyEventDraftRequiresExplicitDiscardAndNeverWritesOnCancel() = fixture { f ->
        runDesktopComposeUiTest(width=1024,height=1000) {
            setContent { DesktopApp(f.reads,f.planner,f.profileSettings,f.eventEditor,f.taskEditor,null,{},
                f.run,f.agent,f.secrets,f.enrollments,f.ids,f.conversationSettings,
                DesktopScheduleScreenCoordinator(f.reads,f.date,f.zone),DesktopNavigation(),false) }
            onNodeWithText("New Event").performClick()
            onNodeWithText("Title").performTextInput("Uncommitted draft")
            onNodeWithText("Cancel").performClick()
            onNodeWithText("Discard unsaved changes?").assertExists()
            onNodeWithText("Keep editing").performClick()
            onNodeWithText("Uncommitted draft").assertExists()
            onNodeWithText("Cancel").performClick()
            onNodeWithText("Discard draft").performClick()
            onNodeWithText("Uncommitted draft").assertDoesNotExist()
            runBlocking { check(f.journal.timeline().size==4) }
        }
    }

    @Test fun dayActionsUseSharedHierarchyAndFullTargetsInBothThemes() = fixture { f ->
        for (dark in listOf(false,true)) runDesktopComposeUiTest(width=1024,height=900) {
            val nav=DesktopNavigation()
            setContent { DesktopApp(f.reads,f.planner,f.profileSettings,f.eventEditor,f.taskEditor,null,{},
                f.run,f.agent,f.secrets,f.enrollments,f.ids,f.conversationSettings,
                DesktopScheduleScreenCoordinator(f.reads,f.date,f.zone),nav,dark) }
            for (destination in listOf(DesktopDestination.TODAY,DesktopDestination.CALENDAR)) {
                runOnIdle { nav.open(destination) }
                waitUntil(timeoutMillis=10000) { onAllNodesWithTag("schedule-ready").fetchSemanticsNodes().isNotEmpty() }
                listOf("Previous day" to ActionRole.TERTIARY,"Next day" to ActionRole.TERTIARY,
                    "New Event" to ActionRole.PRIMARY,"New Task" to ActionRole.SECONDARY).forEach { (label,role) ->
                    onNodeWithText(label).assert(SemanticsMatcher.expectValue(ActionRoleKey,role)).assertHeightIsAtLeast(48.dp)
                }
                onNodeWithText("Back").assertDoesNotExist()
            }
            check(f.providerRequests==0)
        }
    }

    @Test fun observedUnsupportedToolsRemainChatOnlyWithoutMutation() {
        val probes=ProviderProbePresentation()
        fixture(onProbe=probes::record) { f ->
            runBlocking {
                val config=f.agent.providerConfigs().single()
                f.agent.saveProviderConfig(config.copy(toolCallingSupported=true))
            }
            runDesktopComposeUiTest(width=1024,height=1440) {
                val nav=DesktopNavigation().also { it.open(DesktopDestination.AGENT) }
                setContent { DesktopApp(f.reads,f.planner,f.profileSettings,f.eventEditor,f.taskEditor,null,{},
                    f.run,f.agent,f.secrets,f.enrollments,f.ids,f.conversationSettings,
                    DesktopScheduleScreenCoordinator(f.reads,f.date,f.zone),nav,false,providerProbes=probes) }
                waitUntil(timeoutMillis=10000) { onAllNodesWithTag("agent-ready").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Command").performTextInput("Create an event")
                onNodeWithText("Send").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis=10000) { onAllNodesWithText("Turn completed.").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Chat only").assertExists()
                runBlocking {
                    check(probes.observation?.second==dev.agenticscheduler.agent.provider.ProviderProbeResult.Unsupported)
                    check(f.providerRequests==2) // Existing synthetic probe, then explicit ordinary chat.
                    check(f.sentToolSchemas==0)
                    check(f.agent.messages(f.threadId).last().content=="No change was made. Prose is not a mutation.")
                    check(f.agent.toolCalls(f.threadId).isEmpty()); check(f.agent.actions(f.threadId).isEmpty())
                    check(f.journal.timeline().size==4)
                }
            }
        }
    }

    private fun fixture(onProbe: (dev.agenticscheduler.agent.history.ProviderConfig, dev.agenticscheduler.agent.provider.ProviderProbeResult)->Unit = { _, _ -> }, test: (D10FixtureGraph)->Unit) {
        val file=File.createTempFile("d10-ui-", ".db")
        val db=openDesktopDatabase(file.absolutePath)
        val f=D10FixtureGraph(db,onProbe)
        try { runBlocking { f.seed() }; test(f) } finally { f.client.close(); db.close(); file.delete() }
    }
}
