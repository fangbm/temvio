package dev.agenticscheduler.desktop

import androidx.room3.useReaderConnection
import dev.agenticscheduler.agent.context.*
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.fixtures.D10AgentFixtureGraph
import dev.agenticscheduler.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

class AgentContextAttachmentTest {
    private val marker = "00000000-0000-7000-8000-000000009900"
    private val a = AttachedAgentContext(AgentContextAnchor.Course(CourseId(marker)), "DISPLAY_ONLY_CANARY")
    private val b = AttachedAgentContext(AgentContextAnchor.Task(TaskId("00000000-0000-7000-8000-000000009901")), "OTHER_DISPLAY_CANARY")

    @Test fun attachReplaceClearArePassiveAndDraftIndependent() = fixture { f ->
        val c=f.workspace();c.refresh();val before=c.messages;val history=f.base.journal.timeline()
        c.editCommand("Exact\ncommand");check(c.attachContext(a));check(c.attachedContext==a)
        check(c.command=="Exact\ncommand");check(c.attachContext(b));c.clearContext()
        check(c.attachedContext==null);check(c.command=="Exact\ncommand");check(c.messages==before)
        check(f.requests==0);check(f.base.journal.timeline()==history)
    }
    @Test fun explicitSendHandsOnlyExactTypedAnchorToRealRuntimeAndStoresOriginalUser() = fixture { f ->
        val c=f.workspace();c.refresh();c.attachContext(a);c.editCommand("  Exact user\ncommand  ");c.send()
        check(contents(f).count {it==a.anchor.renderForModel()}==1)
        check("DISPLAY_ONLY_CANARY" !in f.bodies.single());check(c.messages.last {it.role==AgentMessageRole.USER}.content=="  Exact user\ncommand  ")
        check(c.attachedContext==a);check(c.calls.isEmpty());check(c.actions.isEmpty())
    }
    @Test fun replacingAWithBSendsOnlyB() = fixture { f ->
        val c=f.workspace();c.refresh();c.attachContext(a);c.attachContext(b);c.editCommand("B only");c.send()
        check(b.anchor.renderForModel() in contents(f));check(marker !in f.bodies.single());check("OTHER_DISPLAY_CANARY" !in f.bodies.single())
    }
    @Test fun clearAfterSendRestoresNormalNoAnchorRequest() = fixture { f ->
        val c=f.workspace();c.refresh();c.attachContext(a);c.editCommand("First");c.send();c.clearContext();c.editCommand("Second");c.send()
        check("LOCAL_UI_REFERENT" !in f.bodies.last());check(marker !in f.bodies.last())
        check(c.messages.filter {it.role==AgentMessageRole.USER}.takeLast(2).map {it.content}==listOf("First","Second"))
    }
    @Test fun immutableSubmissionFrameRejectsChangingAttachmentThreadOrDraftDuringFlight() = fixture { f ->
        val c=f.workspace();c.refresh();c.attachContext(a);c.editCommand("Captured command")
        f.requestEntered=CompletableDeferred();f.requestGate=CompletableDeferred()
        coroutineScope {
            val sent=async {c.send()};f.requestEntered!!.await();check(c.busy)
            check(!c.attachContext(b));c.clearContext();c.editCommand("Later command");c.newConversation();c.send()
            check(c.attachedContext==a);check(c.selectedThread==f.base.threadId);check(c.command=="Captured command")
            f.requestGate!!.complete(Unit);sent.await()
        }
        check(f.requests==1);check(a.anchor.renderForModel() in contents(f));check(c.messages.last {it.role==AgentMessageRole.USER}.content=="Captured command")
    }
    @Test fun visibleSessionAttachmentSurvivesThreadSwitchAndRefresh() = fixture { f ->
        val c=f.workspace();c.refresh();c.attachContext(a);c.newConversation();val other=c.selectedThread
        c.refresh();check(c.attachedContext==a);c.selectThread(f.base.threadId);check(c.attachedContext==a)
        check(other!=c.selectedThread);check(f.requests==0)
    }
    @Test fun confirmationUsesOriginalPreviewWithoutNewAttachment() = fixture { f ->
        val c=f.workspace();f.prepareScene("confirmation",c);val original=c.pending!!;val preview=original.previewJson
        c.attachContext(a);c.inspect(original.id);c.closeOverlay();c.reviewPending();check(c.pending?.previewJson==preview)
        c.confirm(original.id,true);check(c.results.single().callId==original.id);check(c.results.single().mutationIds.size==1)
        check("LOCAL_UI_REFERENT" !in f.bodies.last());check(marker !in f.bodies.last());check(c.attachedContext==a)
    }
    @Test fun newCoordinatorAndReopenedDatabaseNeverReconstructContextButRestorePending() = fixture { f ->
        val c=f.workspace();f.prepareScene("confirmation",c);c.attachContext(a);val original=c.pending!!
        val fresh=f.workspace();fresh.refresh();check(fresh.attachedContext==null);check(fresh.pending==original)
        val path=checkNotNull(fixturePath)
        f.close();f.product.db.close()
        val reopened=openDesktopDatabase(path)
        val newProcessGraph=D10AgentFixtureGraph(reopened)
        try {
            val freshProcess=newProcessGraph.workspace();freshProcess.refresh()
            check(freshProcess.attachedContext==null);check(freshProcess.pending==original)
        } finally {newProcessGraph.close();reopened.close()}
    }
    @Test fun localAnchorAndLabelNeverEnterProvenanceOrExportSource() = fixture { f ->
        val c=f.workspace();c.refresh();c.newConversation();c.attachContext(a);c.editCommand("Exportable plain command");c.send()
        val snapshot=RoomAgentHistoryExportSource(f.product.db).snapshot().toString()
        check("Exportable plain command" in snapshot);check(marker !in snapshot);check("DISPLAY_ONLY_CANARY" !in snapshot);check("LOCAL_UI_REFERENT" !in snapshot)
        f.product.db.useReaderConnection { connection ->
            for(table in listOf("agent_message","context_summary","agent_local_turn_member","agent_thread")) {
                connection.usePrepared("SELECT * FROM $table") { s ->
                    while(s.step()) for(i in 0 until s.getColumnCount()) if(!s.isNull(i)) {
                        val text=runCatching {s.getText(i)}.getOrDefault("")
                        check(marker !in text);check("DISPLAY_ONLY_CANARY" !in text);check("LOCAL_UI_REFERENT" !in text)
                    }
                }
            }
        }
    }
    @Test fun exactTaskAndAllCalendarSourceReferencesBeatBroadViewport() = fixture { f ->
        val task=f.base.tasks.observeTasks().first().single();val viewport=calendarViewport(f.base.date,CalendarMode.WEEK,f.base.zone)
        check(coreAgentContext(CoreSelection.TaskDetail(task.id),viewport,listOf(task),null)?.anchor==AgentContextAnchor.Task(task.id))
        val refs=listOf(CalendarSourceRef.Event(EventId(marker)),CalendarSourceRef.FocusBlock(FocusBlockId(marker)),
            CalendarSourceRef.CourseSession(CourseOccurrenceKey(CourseScheduleRuleId(marker),AcademicWeekNumber(3))),CalendarSourceRef.Exam(ExamId(marker)))
        refs.forEach {ref -> check(coreAgentContext(CoreSelection.Calendar(ref),viewport,emptyList(),null)?.anchor==AgentContextAnchor.CalendarSource(ref))}
    }
    @Test fun exactViewportDateRangeAndZoneHaveNoCopiedCalendarFacts() = fixture { f ->
        val viewport=CalendarViewport(LocalDate(2026,10,1),LocalDate(2026,11,12),TimeZone.of("Asia/Tokyo"))
        val context=coreAgentContext(null,viewport,emptyList(),null)!!;check(context.anchor==AgentContextAnchor.Viewport(viewport))
        check(coreAgentContext(null,null,emptyList(),null)==null);check("Design review" !in context.anchor.renderForModel())
        val c=f.workspace();c.refresh();c.attachContext(context);c.editCommand("Discuss visible dates");c.send();check(context.anchor.renderForModel() in contents(f))
    }
    @Test fun existingPlannerAndHistorySelectionsProduceExactTypedReferences() = fixture { f ->
        val planner=f.product.workspace();planner.refresh();check(planner.agentContext()==null)
        planner.draft=planner.draft.copy(profileId=f.product.configured.id)
        check(planner.agentContext()?.anchor==AgentContextAnchor.PlanningProfile(f.product.configured.id))
        val history=f.product.history();history.refresh();check(history.agentContext()==null)
        val mutation=f.base.journal.timeline().first().operation.mutationId;history.select(mutation)
        check(history.agentContext()?.anchor==AgentContextAnchor.Mutation(dev.agenticscheduler.sync.MutationId(mutation)))
        history.closeDetail();check(history.agentContext()==null)
    }
    @Test fun academicCourseAndExamUseExistingTypedIdsWithoutSyntheticFallback() {
        val course=Course(CourseId(marker),SemesterId(marker),"Display Course",null)
        val exam=Exam(ExamId(marker),SemesterId(marker),course.id,"Display Exam",ExamSchedule.Unscheduled)
        check(AcademicRecord.Subject(course).agentContext()?.anchor==AgentContextAnchor.Course(course.id))
        check(AcademicRecord.Assessment(exam).agentContext()?.anchor==AgentContextAnchor.CalendarSource(CalendarSourceRef.Exam(exam.id)))
        val year=AcademicYear(AcademicYearId(marker),"Display Year",LocalDate(2026,9,1),LocalDate(2027,9,1))
        check(AcademicRecord.Year(year).agentContext()==null)
    }
    private fun contents(f:D10AgentFixtureGraph)=Json.parseToJsonElement(f.bodies.last()).jsonObject["messages"]!!.jsonArray.mapNotNull {it.jsonObject["content"]?.jsonPrimitive?.content}
    private var fixturePath: String? = null
    private fun fixture(test:suspend (D10AgentFixtureGraph)->Unit) = runBlocking {
        val file=File.createTempFile("d10-context-", ".db");fixturePath=file.absolutePath
        val db=openDesktopDatabase(file.absolutePath);val f=D10AgentFixtureGraph(db)
        try {f.seed();test(f)} finally {f.close();db.close();file.delete();fixturePath=null}
    }
}
