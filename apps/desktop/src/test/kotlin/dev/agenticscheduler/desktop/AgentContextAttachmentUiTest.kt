package dev.agenticscheduler.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import dev.agenticscheduler.agent.context.*
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.fixtures.D10AgentFixtureGraph
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.ui.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class AgentContextAttachmentUiTest {
    @Test fun actualTaskDetailContextualActionNavigatesWithoutSend() = fixture { f ->
        runDesktopComposeUiTest(width=1280,height=800) {
            val c=f.workspace();val core=CoreScreenCoordinator();val nav=DesktopNavigation().also {it.open(DesktopDestination.TASKS)}
            setContent {app(f,c,core,nav)}
            waitUntil(timeoutMillis=10000) {onAllNodesWithText("View Task").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithText("View Task").performClick();onNodeWithTag("agent-discuss-task").performClick()
            waitUntil(timeoutMillis=10000) {c.loaded};val task=runBlocking {f.base.tasks.observeTasks().first().single()}
            check(c.attachedContext?.anchor==AgentContextAnchor.Task(task.id));check(nav.current==DesktopDestination.AGENT)
            onNodeWithTag("agent-context-label").assertIsDisplayed();onNodeWithTag("agent-command").assertIsEnabled()
            check(f.requests==0);check(c.messages.size==2)
        }
    }
    @Test fun actualCalendarDetailsPreserveAllFourTypedSourceReferences() = fixture { f ->
        val id="00000000-0000-7000-8000-000000008800"
        val refs=listOf(CalendarSourceRef.Event(EventId(id)),CalendarSourceRef.FocusBlock(FocusBlockId(id)),
            CalendarSourceRef.CourseSession(CourseOccurrenceKey(CourseScheduleRuleId(id),AcademicWeekNumber(4))),CalendarSourceRef.Exam(ExamId(id)))
        val projection=CalendarProjectionResult(refs.map {CalendarItem.DateOnly(it,"Synthetic selected source",f.base.date)}.toImmutableList(),emptyList<CalendarConflict>().toImmutableList(),emptyList<CalendarProjectionIssue>().toImmutableList())
        runDesktopComposeUiTest(width=1280,height=800) {
            val core=CoreScreenCoordinator();var attached by mutableStateOf<AttachedAgentContext?>(null)
            val tasks=runBlocking {f.base.tasks.observeTasks().first()};val blocks=runBlocking {f.base.tasks.observeFocusBlocks().first()}
            setContent {TemvioTheme(false) {CoreSchedulingScreen(CoreScreen.CALENDAR,core,f.base.date,f.base.zone,f.product.now,projection,
                ConflictAwareRead.Projected(tasks,emptyList()),ConflictAwareRead.Projected(blocks,emptyList()),f.base.reads,{}, {}, {}, {}, {},
                {attached=it},{},{},null,{})}}
            refs.forEach {ref ->
                runOnIdle {core.selection=CoreSelection.Calendar(ref)}
                onNodeWithTag("agent-discuss-calendar").performClick()
                runOnIdle {check(attached?.anchor==AgentContextAnchor.CalendarSource(ref));check(core.selection==CoreSelection.Calendar(ref))}
            }
            runOnIdle {core.selection=null}
            onNodeWithTag("agent-open-viewport").performScrollTo().performClick()
            check(attached?.anchor==AgentContextAnchor.Viewport(calendarViewport(f.base.date,CalendarMode.DAY,f.base.zone)))
            runOnIdle {core.mode=CalendarMode.WEEK}
            onNodeWithTag("agent-open-viewport").performScrollTo().performClick()
            check(attached?.anchor==AgentContextAnchor.Viewport(calendarViewport(f.base.date,CalendarMode.WEEK,f.base.zone)))
            check(f.requests==0)
        }
    }
    @Test fun actualTodayBroadEntryAttachesOnlyItsExactFiniteViewportWithoutSend() = fixture { f ->
        runDesktopComposeUiTest(width=1280,height=800) {
            val c=f.workspace();val core=CoreScreenCoordinator();val nav=DesktopNavigation()
            val schedule=DesktopScheduleScreenCoordinator(f.base.reads,f.base.date,f.base.zone)
            setContent {app(f,c,core,nav,schedule)}
            waitUntil(timeoutMillis=10000) {onAllNodesWithTag("schedule-ready").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithTag("agent-open-viewport").performScrollTo().performClick();waitUntil(timeoutMillis=10000) {c.loaded}
            check(c.attachedContext?.anchor==AgentContextAnchor.Viewport(calendarViewport(f.base.date,CalendarMode.DAY,f.base.zone)))
            check(f.requests==0);check(c.messages.size==2)
        }
    }
    @Test fun desktopGlobalEntryUsesCurrentExactViewportAndSelectionThenPlainOtherDestination() = fixture { f ->
        runDesktopComposeUiTest(width=1280,height=800) {
            val c=f.workspace();val core=CoreScreenCoordinator();val nav=DesktopNavigation()
            val schedule=DesktopScheduleScreenCoordinator(f.base.reads,f.base.date,f.base.zone)
            setContent {app(f,c,core,nav,schedule)}
            waitUntil(timeoutMillis=10000) {onAllNodesWithTag("schedule-ready").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithTag("global-agent").performClick();waitUntil(timeoutMillis=10000) {c.loaded}
            check(c.attachedContext?.anchor==AgentContextAnchor.Viewport(calendarViewport(f.base.date,CalendarMode.DAY,f.base.zone)))
            onNodeWithTag("desktop-nav-CALENDAR").performClick();onNodeWithTag("calendar-mode-WEEK").performClick()
            onNodeWithTag("global-agent").performClick();check(c.attachedContext?.anchor==AgentContextAnchor.Viewport(calendarViewport(f.base.date,CalendarMode.WEEK,f.base.zone)))
            onNodeWithTag("desktop-nav-CALENDAR").performClick()
            val event=runBlocking {f.base.events.observeAll().first().first()};val source=CalendarSourceRef.Event(event.id)
            runOnIdle {core.selection=CoreSelection.Calendar(source)}
            onNodeWithTag("global-agent").performClick();check(c.attachedContext?.anchor==AgentContextAnchor.CalendarSource(source))
            onNodeWithTag("desktop-nav-TASKS").performClick();onNodeWithText("View Task").performClick();onNodeWithTag("global-agent").performClick()
            val task=runBlocking {f.base.tasks.observeTasks().first().single()};check(c.attachedContext?.anchor==AgentContextAnchor.Task(task.id))
            onNodeWithTag("agent-clear-context").performClick();onNodeWithTag("desktop-nav-INSIGHTS").performClick();onNodeWithTag("global-agent").performClick()
            check(c.attachedContext==null);check(f.requests==0);check(c.messages.size==2)
        }
    }
    @Test fun attachedContextAndDraftSurviveThemeResizeAndClearRemainsReachableAt200Percent() = fixture { f ->
        runDesktopComposeUiTest(width=1280,height=800) {
            val c=f.workspace();val attachment=taskAgentContext(TaskId("00000000-0000-7000-8000-000000008801"),"A long readable synthetic selected task")
            c.attachContext(attachment);var width by mutableStateOf(1280f);var dark by mutableStateOf(false);var scale by mutableStateOf(1f)
            setContent {CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {TemvioTheme(dark) {Box(Modifier.requiredWidth(width.dp)) {AgentWorkspaceScreen(c,rememberCoroutineScope(),{},{},{})}}}}
            waitUntil(timeoutMillis=10000) {c.loaded};onNodeWithTag("agent-command").performTextInput("Unsent exact draft")
            runOnIdle {width=640f;dark=true;scale=2f};onNodeWithTag("agent-clear-context").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
            check(c.attachedContext==attachment);check(c.command=="Unsent exact draft");check(f.requests==0)
            onNodeWithTag("agent-clear-context").performClick();check(c.attachedContext==null);check(c.command=="Unsent exact draft")
            onNodeWithTag("agent-send").assertIsDisplayed();check(f.requests==0)
        }
    }
    @Composable private fun app(f:D10AgentFixtureGraph,c:AgentWorkspaceCoordinator,core:CoreScreenCoordinator,nav:DesktopNavigation,
        schedule:DesktopScheduleScreenCoordinator=remember {DesktopScheduleScreenCoordinator(f.base.reads,f.base.date,f.base.zone)}) {
        DesktopApp(f.base.reads,f.base.planner,f.base.profileSettings,f.base.eventEditor,f.base.taskEditor,null,{},f.run,f.base.agent,f.base.secrets,
            f.base.enrollments,f.base.ids,f.base.conversationSettings,scheduleSession=schedule,coreSession=core,navigationSession=nav,
            initialDark=false,presentationNow=f.product.now,agentWorkspaceSession=c)
    }
    private fun fixture(test:(D10AgentFixtureGraph)->Unit) {
        val file=File.createTempFile("d10-context-ui-", ".db");val db=openDesktopDatabase(file.absolutePath);val f=D10AgentFixtureGraph(db)
        try {runBlocking {f.seed()};test(f)} finally {f.close();db.close();file.delete()}
    }
}
