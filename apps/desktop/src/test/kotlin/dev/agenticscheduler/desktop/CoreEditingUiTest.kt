package dev.agenticscheduler.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.fixtures.D10CoreFixtureGraph
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class CoreEditingUiTest {
    @Test fun keyboardCanReachAndExplicitlySaveAnAcademicFormWithoutImplicitSubmit() = fixture { f ->
        val c=AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
        val count=runBlocking { f.base.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1100) {
            val b=f.base; val nav=DesktopNavigation().also { it.open(DesktopDestination.COURSES) }
            setContent { DesktopApp(b.reads,b.planner,b.profileSettings,b.eventEditor,b.taskEditor,null,{},b.run,b.agent,b.secrets,b.enrollments,b.ids,b.conversationSettings,
                remember { DesktopScheduleScreenCoordinator(b.reads,b.date,b.zone) },nav,false,academicService=f.authoring,academicSession=c,presentationNow=f.now) }
            waitUntil(timeoutMillis=10000) { c.facts!=null }
            runOnIdle { c.edit(AcademicRecord.Subject(f.course)) }
            onNodeWithText("Name",substring=false).performTextReplacement("Keyboard-authored Course")
            onNodeWithText("Name",substring=false).performKeyInput { pressKey(Key.Tab) }
            runBlocking { check(b.journal.timeline().size==count) }
            onNodeWithText("Save",substring=false).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithText("Save",substring=false).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis=10000) { c.draft==null && !c.saving }
            runBlocking { check(b.journal.timeline().size==count+1) }
        }
    }
    @Test fun explicitTaskCreateUsesNormalD7AndNoSubmitOnFilterThemeNavigation() = fixture { f ->
        val b = f.base; val count = runBlocking { b.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1100) {
            val nav = DesktopNavigation().also { it.open(DesktopDestination.TASKS) }
            setContent { DesktopApp(b.reads,b.planner,b.profileSettings,b.eventEditor,b.taskEditor,null,{},b.run,b.agent,b.secrets,b.enrollments,b.ids,b.conversationSettings,
                remember { DesktopScheduleScreenCoordinator(b.reads,b.date,b.zone) },nav,false,academicService=f.authoring,presentationNow=f.now) }
            onNodeWithText("New Task",substring=false).performClick(); onNodeWithText("Title",substring=false).performTextInput("Explicit created task")
            onNodeWithText("Remaining effort (optional)").performTextInput("1h")
            onNodeWithText("Priority: NORMAL").performClick(); onNodeWithText("Priority: HIGH").assertExists()
            onNodeWithText("Save",substring=false).performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Title",substring=false).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("theme-toggle").performClick(); onNodeWithText("All",substring=false).performClick(); onNodeWithTag("desktop-nav-CALENDAR").performClick()
            runBlocking { val put = b.journal.timeline().drop(count).single().operation.orderedMutations.single() as TaskPut; check(put.before == null && put.after.title == "Explicit created task"); check(put.after.priority == TaskPriorityImage.HIGH) }
            check(b.providerRequests == 0)
        }
    }
    @Test fun staleTaskDraftDoesNotOverwriteAndExplicitReloadIsAvailable() = fixture { f ->
        val b = f.base; val task = runBlocking { b.tasks.observeTasks().first().single() }; val count = runBlocking { b.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1100) {
            val nav = DesktopNavigation().also { it.open(DesktopDestination.TASKS) }
            setContent { DesktopApp(b.reads,b.planner,b.profileSettings,b.eventEditor,b.taskEditor,null,{},b.run,b.agent,b.secrets,b.enrollments,b.ids,b.conversationSettings,
                remember { DesktopScheduleScreenCoordinator(b.reads,b.date,b.zone) },nav,false,presentationNow=f.now) }
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("View Task",substring=false).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("View Task",substring=false).performClick(); onNodeWithText("Edit Task",substring=false).performClick()
            onNodeWithText("Title",substring=false).performTextReplacement("Useful unsaved Task draft")
            runBlocking { check(b.taskEditor.update(task.input("External change"),expectedBefore=task) is EditingResult.Success) }
            onNodeWithText("Save",substring=false).performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Task changed. Draft retained. Explicitly reload before saving.").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Useful unsaved Task draft").assertExists(); runBlocking { check(b.journal.timeline().size == count+1) }
            onNodeWithText("Reload source (discard draft)").performScrollTo().performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("External change").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Useful unsaved Task draft").assertDoesNotExist()
        }
    }
    @Test fun openTaskConflictIsVisibleAndNormalSaveCannotBypassGuard() = fixture { f ->
        val b = f.base; val space = SyncSpaceId(id(6000)); val receive = RoomSyncReceiveRepository(b.db)
        runBlocking {
            val put = b.journal.timeline().map { it.operation.orderedMutations.single() }.filterIsInstance<TaskPut>().single()
            fun op(value: EntityMutation,n: Int) = SyncOperation(id(n),DvvSnapshot(emptyList(),DotSnapshot(id(n+1),0)),HlcSnapshot(1,0,id(n+1)),MutationOrigin.User,listOf(value))
            val operations = listOf(op(put,6001),op(put.copy(after=put.after.copy(title="Concurrent other title")),6003))
            val participants = operations.map { SyncConflictParticipant(MutationId(it.mutationId),it.dvv,LocalJournalCodec.encode(it)) }
            receive.saveConflict(SyncConflict(id(6100),space,listOf(SyncConflictEntityRef(EntityKind.TASK,put.entityId,listOf("title"))),participants,MutationId(operations.first().mutationId),SyncConflictKind.SEMANTIC,commonCausalContextOf(participants),SyncConflictStatus.OPEN))
        }
        val query = ActiveConflictAwareSourceFactQuery(ConflictAwareProjection(receive),space)
        val reads = ConflictAwareSourceFactReadService(b.events,b.tasks,b.profiles,b.academics,query)
        val editor = TaskEditingService(b.tasks,b.ids,b.mutations,SyncConflictWriteGuard(receive,space))
        val count = runBlocking { b.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1100) {
            val nav = DesktopNavigation().also { it.open(DesktopDestination.TASKS) }
            setContent { DesktopApp(reads,b.planner,b.profileSettings,b.eventEditor,editor,null,{},b.run,b.agent,b.secrets,b.enrollments,b.ids,b.conversationSettings,
                remember { DesktopScheduleScreenCoordinator(reads,b.date,b.zone) },nav,false,presentationNow=f.now) }
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Provisional Tasks").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Prepare research notes").assertExists(); onNodeWithText("View Task",substring=false).performClick(); onNodeWithText("Edit Task",substring=false).performClick()
            onNodeWithText("Title",substring=false).performTextReplacement("Ordinary Save is not resolution")
            onNodeWithText("Save",substring=false).performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("This change intersects an unresolved sync conflict. Resolve it before editing.").fetchSemanticsNodes().isNotEmpty() }
            runBlocking { check(b.journal.timeline().size == count); check(receive.conflicts(space).single().status == SyncConflictStatus.OPEN) }
        }
    }
    @Test fun explicitEventCreateAndSelectedSourceEditCommitBeforeImages() = fixture { f ->
        val b=f.base; val core=CoreScreenCoordinator(); val count=runBlocking { b.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1100) {
            val nav=DesktopNavigation().also { it.open(DesktopDestination.CALENDAR) }
            setContent { DesktopApp(b.reads,b.planner,b.profileSettings,b.eventEditor,b.taskEditor,null,{},b.run,b.agent,b.secrets,b.enrollments,b.ids,b.conversationSettings,
                remember { DesktopScheduleScreenCoordinator(b.reads,b.date,b.zone) },nav,false,coreSession=core,presentationNow=f.now) }
            onNodeWithText("New Event",substring=false).performClick()
            onNodeWithText("Title",substring=false).performTextInput("Explicit UI Event")
            onNodeWithText("Start (YYYY-MM-DDTHH:MM)").performTextInput("2026-10-06T16:00")
            onNodeWithText("End exclusive",substring=false).performTextInput("2026-10-06T17:00")
            onNodeWithText("Save",substring=false).performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Title",substring=false).fetchSemanticsNodes().isEmpty() }
            val create=runBlocking { b.journal.timeline().drop(count).single().operation.orderedMutations.single() as EventPut }
            check(create.before==null && create.after.title=="Explicit UI Event")
            runOnIdle { core.selection=CoreSelection.Calendar(dev.agenticscheduler.application.calendar.CalendarSourceRef.Event(dev.agenticscheduler.domain.id.EventId(create.entityId))) }
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Edit Event",substring=false).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Edit Event",substring=false).performClick()
            onNodeWithText("Title",substring=false).performTextReplacement("Edited UI Event")
            onNodeWithText("Save",substring=false).performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Title",substring=false).fetchSemanticsNodes().isEmpty() }
            runBlocking {
                val update=b.journal.timeline().drop(count).last().operation.orderedMutations.single() as EventPut
                check(b.journal.timeline().size==count+2 && update.before==create.after && update.after.title=="Edited UI Event")
            }
            check(b.providerRequests==0)
        }
    }
    @Test fun eventDstGapRejectsWithoutWriteAndDraftCanBeDiscarded() = fixture { f ->
        val b=f.base; val count=runBlocking { b.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1100) {
            setContent { DesktopApp(b.reads,b.planner,b.profileSettings,b.eventEditor,b.taskEditor,null,{},b.run,b.agent,b.secrets,b.enrollments,b.ids,b.conversationSettings,
                remember { DesktopScheduleScreenCoordinator(b.reads,b.date,b.zone) },DesktopNavigation(),false,presentationNow=f.now) }
            onNodeWithText("New Event",substring=false).performClick(); onNodeWithText("Title",substring=false).performTextInput("DST gap")
            onNodeWithText("Start (YYYY-MM-DDTHH:MM)").performTextInput("2026-03-08T02:30")
            onNodeWithText("End exclusive",substring=false).performTextInput("2026-03-08T04:00")
            onNodeWithText("Time zone",substring=false).performTextReplacement("America/New_York")
            onNodeWithText("Save",substring=false).performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("ZonedTimeTransitionRejected",substring=true).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { check(b.journal.timeline().size==count) }
            onNodeWithText("Cancel",substring=false).performClick(); onNodeWithText("Discard draft").performClick()
        }
    }
    private fun Task.input(title: String) = UpdateTaskInput(id,title,status,priority,effort.estimated,effort.completed,effort.remaining,null)
    private fun fixture(test: (D10CoreFixtureGraph)->Unit) {
        val file=File.createTempFile("d10-edit-", ".db"); val db=openDesktopDatabase(file.absolutePath); val f=D10CoreFixtureGraph(db)
        try { runBlocking { f.seed() }; test(f) } finally { f.base.client.close(); db.close(); file.delete() }
    }
    private fun id(n: Int)="01900000-0000-7000-8000-${n.toString().padStart(12,'0')}"
}
