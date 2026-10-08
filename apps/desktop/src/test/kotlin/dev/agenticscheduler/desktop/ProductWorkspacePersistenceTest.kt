package dev.agenticscheduler.desktop

import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.fixtures.D10ProductFixtureGraph
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.File
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours

/** Real Room and existing Application commands, not mocked Planner/Undo outcomes. */
class ProductWorkspacePersistenceTest {
    @Test fun previewCancelDoNotWriteAndApplyConsumesOneJournalGroup() = fixture { f -> runBlocking {
        val c=f.workspace();c.refresh();c.draft=f.request()
        val count=f.base.journal.timeline().size;val task=f.base.tasks.observeTasks().first().single()
        c.requestPreview();check(c.preview is PlannerPreview.Applicable)
        check(f.base.tasks.observeFocusBlocks().first().isEmpty());check(f.base.journal.timeline().size==count)
        c.cancelPreview();check(c.preview==null);check(f.base.journal.timeline().size==count)
        c.requestPreview();val proposed=(c.preview as PlannerPreview.Applicable).branch.mutations.size;check(proposed>0)
        c.apply(f.now);check(c.preview==null);check(c.message!!.startsWith("Applied"))
        val entry=f.base.journal.timeline().drop(count).single();check(entry.operation.origin==MutationOrigin.Planner)
        check(entry.operation.orderedMutations.size==proposed);check(f.base.tasks.getTask(task.id)==task)
        c.apply(f.now);check(f.base.journal.timeline().size==count+1);check(f.base.providerRequests==0)
    } }
    @Test fun sourceChangeMakesApplyStaleWithNoPartialWrites() = fixture {f -> runBlocking {
        val c=f.workspace();c.refresh();c.draft=f.request();c.requestPreview()
        f.base.eventEditor.create(CreateEventInput("New obstruction",EventTimeInput.Zoned(kotlinx.datetime.LocalDateTime(2026,10,6,11,0),kotlinx.datetime.LocalDateTime(2026,10,6,12,0),f.base.zone),Flexibility.HARD,PinState.UNPINNED))
        val count=f.base.journal.timeline().size;c.apply(f.now)
        check((c.preview as PlannerPreview.Applicable).branch.status==PlanBranchStatus.STALE)
        check(f.base.journal.timeline().size==count);check(f.base.tasks.observeFocusBlocks().first().isEmpty())
        c.apply(f.now);check(f.base.journal.timeline().size==count)
    } }
    @Test fun unconfiguredAndInvalidRequestsNeverInventDefaults() = fixture {f -> runBlocking {
        val c=f.workspace();c.refresh();c.draft=f.request().copy(profileId=f.unconfigured.id);c.requestPreview()
        check(c.preview is PlannerPreview.InvalidInput);val count=f.base.journal.timeline().size
        c.cancelPreview();c.draft=f.request().copy(referenceNow="bad");c.requestPreview();check(c.preview==null)
        c.createName="   ";c.createUnconfigured();check(c.createName!=null)
        check(f.base.journal.timeline().size==count)
        c.createName="Personal explicit draft";c.createUnconfigured();check(c.createName==null)
        check(c.profiles.last().configuration==PlanningProfileConfiguration.Unconfigured)
        check(f.base.journal.timeline().last().operation.origin==MutationOrigin.User)
    } }
    @Test fun localReflowKeepsUnrelatedBlocksFixedAndRemainsPreviewUntilApply() = fixture {f -> runBlocking {
        val c=f.workspace();c.refresh();c.draft=f.request();c.requestPreview();c.apply(f.now)
        val before=f.base.tasks.observeFocusBlocks().first();check(before.size>=2)
        val affected=before.first();val untouched=before.drop(1)
        c.draft=f.request(PlannerMode.LOCAL_REFLOW).copy(affectedIds=setOf(affected.id),
            disruptedRanges="${affected.time.start} / ${affected.time.endExclusive}")
        c.requestPreview();check(c.preview is PlannerPreview.Applicable)
        check(f.base.tasks.observeFocusBlocks().first()==before)
        c.apply(f.now);check(c.preview==null)
        val after=f.base.tasks.observeFocusBlocks().first();untouched.forEach {check(after.contains(it))}
    } }
    @Test fun profileConflictMakesPreviewStaleAndIsNeverClearedByPresentation() = fixture {f -> runBlocking {
        val c=f.workspace();c.refresh();c.draft=f.request();c.requestPreview();f.profileConflict()
        val count=f.base.journal.timeline().size;c.apply(f.now)
        check((c.preview as PlannerPreview.Applicable).branch.status==PlanBranchStatus.STALE)
        check(f.base.journal.timeline().size==count);check(f.base.tasks.observeFocusBlocks().first().isEmpty())
        check(f.conflicts.listOpen(f.space).size==1)
    } }
    @Test fun taskUpdateUndoUsesCompensationAndPreservesOriginal() = fixture {f -> runBlocking {
        val task=f.base.tasks.observeTasks().first().single()
        val update=f.base.taskEditor.update(UpdateTaskInput(task.id,"Updated notes",task.status,task.priority,2.hours,kotlin.time.Duration.ZERO,2.hours,null)) as EditingResult.Success
        val h=f.history();h.refresh();h.select(update.mutationId!!.value)
        check(h.detail!!.undo==UndoCapability.Available);val count=f.base.journal.timeline().size
        h.undoSelected();check(f.base.journal.timeline().size==count) // explicit confirmation required
        h.confirmUndo=true;h.undoSelected();check(f.base.tasks.getTask(task.id)==task)
        check(f.base.journal.timeline().size==count+1);check(f.base.journal.mutation(update.mutationId!!.value)!=null)
        check(f.base.journal.timeline().last().operation.origin is MutationOrigin.Undo)
        check(h.message!!.startsWith("Compensating"));check(h.detail!!.changes.single().afterImageJson!!.contains("Updated notes"))
    } }
    @Test fun committedUndoRemainsReportedAndConsumedWhenHistoryRefreshFails() = fixture {f -> runBlocking {
        val task=f.base.tasks.observeTasks().first().single()
        val update=f.base.taskEditor.update(UpdateTaskInput(task.id,"Before refresh failure",task.status,task.priority,2.hours,kotlin.time.Duration.ZERO,2.hours,null)) as EditingResult.Success
        var failRead=false
        val read=object:dev.agenticscheduler.application.persistence.HistoryRepository by f.base.journal {
            override suspend fun timeline():List<dev.agenticscheduler.application.persistence.CommittedMutation> {
                check(!failRead) {"Injected read failure after real commit"};return f.base.journal.timeline()
            }
        }
        val h=HistoryScreenCoordinator(HistoryQueryService(read),f.undo)
        h.select(update.mutationId!!.value);h.confirmUndo=true;failRead=true
        val count=f.base.journal.timeline().size;h.undoSelected()
        check(h.message!!.startsWith("Compensating mutation committed"));check(h.message!!.contains("refresh failed"))
        check(h.detail==null);check(f.base.tasks.getTask(task.id)==task);check(f.base.journal.timeline().size==count+1)
        h.confirmUndo=true;h.undoSelected();check(f.base.journal.timeline().size==count+1)
        failRead=false;h.refresh();h.select(update.mutationId!!.value);check(h.detail!=null)
    } }
    @Test fun changedTaskUndoFailsWithoutOverwrite() = fixture {f -> runBlocking {
        val task=f.base.tasks.observeTasks().first().single()
        suspend fun update(title:String)=f.base.taskEditor.update(UpdateTaskInput(task.id,title,task.status,task.priority,2.hours,kotlin.time.Duration.ZERO,2.hours,null)) as EditingResult.Success
        val first=update("First edit");update("Second edit")
        val h=f.history();h.select(first.mutationId!!.value);h.confirmUndo=true
        val count=f.base.journal.timeline().size;h.undoSelected()
        check(h.message!!.startsWith("Undo conflict"));check(f.base.tasks.getTask(task.id)!!.title=="Second edit")
        check(f.base.journal.timeline().size==count)
    } }
    @Test fun groupedPlannerUndoIsAllOrNoneThenCleanGroupUndoesTogether() = fixture {f -> runBlocking {
        val c=f.workspace();c.refresh();c.draft=f.request();c.requestPreview();c.apply(f.now)
        val operation=f.base.journal.timeline().last().operation;check(operation.orderedMutations.size>=2)
        val before=f.base.tasks.observeFocusBlocks().first();val changed=before.first().copy(time=ZonedTimeRange(Instant.parse("2026-10-06T15:00:00Z"),Instant.parse("2026-10-06T15:50:00Z"),f.base.zone))
        f.base.mutations.execute(MutationOrigin.User) {f.base.tasks.upsertFocusBlock(changed);record(FocusBlockPut(before.first().toSemanticImage(),changed.toSemanticImage()))}
        val h=f.history();h.select(operation.mutationId);h.confirmUndo=true;val count=f.base.journal.timeline().size;h.undoSelected()
        check(h.message!!.startsWith("Undo conflict"));check(f.base.journal.timeline().size==count)
        check(f.base.tasks.observeFocusBlocks().first().size==before.size);before.drop(1).forEach {check(f.base.tasks.observeFocusBlocks().first().contains(it))}
        // Restore through the journal seam; group preconditions again match exact original after images.
        f.base.mutations.execute(MutationOrigin.User) {f.base.tasks.upsertFocusBlock(before.first());record(FocusBlockPut(changed.toSemanticImage(),before.first().toSemanticImage()))}
        h.select(operation.mutationId);h.confirmUndo=true;h.undoSelected();check(f.base.tasks.observeFocusBlocks().first().isEmpty())
    } }
    @Test fun provisionalFocusExistenceConflictAllowsPreviewButBlocksAtomicApply() = fixture {f -> runBlocking {
        val task=f.base.tasks.observeTasks().first().single()
        val block=FocusBlock(dev.agenticscheduler.domain.id.FocusBlockId(f.base.ids.next()),task.id,
            ZonedTimeRange(Instant.parse("2026-10-06T09:45:00Z"),Instant.parse("2026-10-06T10:15:00Z"),f.base.zone),Flexibility.FLEXIBLE,PinState.UNPINNED)
        f.base.mutations.execute(MutationOrigin.User) {f.base.tasks.upsertFocusBlock(block);record(FocusBlockPut(null,block.toSemanticImage()))}
        val participants=listOf(FocusBlockPut(null,block.toSemanticImage()),FocusBlockDelete(block.toSemanticImage())).map {mutation ->
            val id=f.base.ids.next();val replica=f.base.ids.next()
            val operation=SyncOperation(id,DvvSnapshot(emptyList(),DotSnapshot(replica,1)),HlcSnapshot(1,0,replica),MutationOrigin.User,listOf(mutation))
            SyncConflictParticipant(MutationId(id),operation.dvv,LocalJournalCodec.encode(operation))
        }.sortedBy {it.mutationId.value}
        val conflict=SyncConflict("focus-existence",f.space,listOf(SyncConflictEntityRef(EntityKind.FOCUS_BLOCK,block.id.value,listOf("existence"))),participants,
            participants.first().mutationId,SyncConflictKind.SEMANTIC,commonCausalContextOf(participants),SyncConflictStatus.OPEN)
        dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner(f.db).inWriteTransaction {f.receive.saveConflict(conflict)}
        val c=f.workspace();c.refresh();c.draft=f.request();c.requestPreview();check(c.preview is PlannerPreview.Applicable)
        val count=f.base.journal.timeline().size;c.apply(f.now)
        check((c.preview as PlannerPreview.Applicable).branch.status==PlanBranchStatus.CONFLICTED)
        check(f.base.journal.timeline().size==count);check(f.base.tasks.observeFocusBlocks().first()==listOf(block))
    } }
    @Test fun profileUndoRechecksOpenD8ConflictInsideApplicationCommand() = fixture {f -> runBlocking {
        f.profileSettings.save(f.configured.copy(name="Edited research hours"), f.configured)
        val operation=f.base.journal.timeline().last().operation
        f.profileConflict()
        val h=f.history();h.select(operation.mutationId);check(h.detail!!.undo==UndoCapability.Available)
        h.confirmUndo=true;val count=f.base.journal.timeline().size;h.undoSelected()
        check(h.message!!.startsWith("Undo blocked"));check(f.base.journal.timeline().size==count)
        check(f.base.profiles.get(f.configured.id)!!.name=="Edited research hours")
    } }
    @Test fun eventUpdateCompensatesButCreateRemainsUnsupported() = fixture {f -> runBlocking {
        val event=f.base.events.observeAll().first().first {it.title=="Design review"}
        val updated=f.base.eventEditor.update(UpdateEventInput(event.id,"Revised review",EventTimeInput.Zoned(kotlinx.datetime.LocalDateTime(2026,10,6,9,30),kotlinx.datetime.LocalDateTime(2026,10,6,10,30),f.base.zone),event.flexibility,event.pinState),expectedBefore=event) as EditingResult.Success
        val h=f.history();h.select(updated.mutationId!!.value);check(h.detail!!.undo==UndoCapability.Available)
        h.confirmUndo=true;h.undoSelected();check(f.base.events.get(event.id)==event)
        val create=f.base.journal.timeline().first().operation.mutationId;h.select(create);check(h.detail!!.undo is UndoCapability.Unsupported)
    } }
    @Test fun historyCursorPaginationIsBoundedUniqueAndReadOnly() = fixture {f -> runBlocking {
        repeat(65) {f.profileSettings.createUnconfigured("Synthetic profile $it")}
        val count=f.base.journal.timeline().size;val h=f.history();h.refresh();check(h.rows.size==50)
        h.loadMore();check(h.rows.size==count);check(h.rows.map {it.operation.mutationId}.distinct().size==count)
        h.loadMore();check(h.exhausted);h.loadMore();check(h.rows.size==count)
        h.select(h.rows.first().operation.mutationId);check(h.detail!!.undo is UndoCapability.Unsupported)
        h.inspectEntity(EntityKind.EVENT,h.detail!!.changes.first().entityId);check(h.entityChanges.isNotEmpty())
        h.closeDetail();h.select("missing");check(h.detail==null);check(f.base.journal.timeline().size==count);check(f.base.providerRequests==0)
    } }
    @Test fun syncMetadataNeverResolvesConflictAndV2GateDoesNotEnableV3() = fixture {f -> runBlocking {
        f.enroll();val conflict=f.profileConflict();val c=f.sync();c.refresh();check(c.activeSpace==f.space)
        check(c.openConflicts.single().participants.size==2);c.inspect(conflict.conflictId)
        check(c.selected!!.status==SyncConflictStatus.OPEN);check(!c.agentBusinessEnabled)
        c.setAgentBusinessFromUser(true);check(c.agentBusinessEnabled)
        check(!f.base.conversationSettings.settings().single().consent)
        check(f.conflicts.listOpen(f.space).single()==conflict)
        check(f.base.providerRequests==0)
    } }
    @Test fun taskCreateAndAcademicAuthoringRemainUnsupportedUndoWithoutWrites() = fixture {f -> runBlocking {
        val taskCreate=f.base.journal.timeline().single {it.operation.orderedMutations.any {m -> m is TaskPut && m.before==null}}
        val authoring=dev.agenticscheduler.application.academic.AcademicAuthoringService(f.base.academics,f.base.ids,f.base.mutations,f.policy,f.source)
        val result=authoring.createAcademicYear(dev.agenticscheduler.application.academic.AcademicYearInput("Synthetic year",kotlinx.datetime.LocalDate(2026,9,1),kotlinx.datetime.LocalDate(2027,9,1)))
        check(result is dev.agenticscheduler.application.academic.AcademicEditingResult.Success)
        val academic=f.base.journal.timeline().last()
        val count=f.base.journal.timeline().size;val h=f.history()
        for(id in listOf(taskCreate.operation.mutationId,academic.operation.mutationId)) {
            h.select(id);check(h.detail!!.undo is UndoCapability.Unsupported);h.confirmUndo=true;h.undoSelected()
            check(f.base.journal.timeline().size==count)
        }
    } }
    @Test fun externallyResolvedConflictLeavesOpenListButRetainsResolvedDetail() = fixture {f -> runBlocking {
        f.enroll();val conflict=f.profileConflict();val c=f.sync();c.refresh();check(c.openConflicts.size==1)
        // Deterministic received-context fixture through typed persistence; resolution itself is the real Application command.
        dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner(f.db).inWriteTransaction {
            val prior=checkNotNull(f.base.journal.localReplicaState())
            val observed=prior.observedContext.toMutableMap()
            conflict.participants.forEach {participant -> participant.dvv.toDottedVersionVector().observedContext().forEach {(replica,counter) -> observed[replica]=maxOf(observed[replica] ?: -1L,counter)}}
            f.base.journal.saveLocalReplicaState(prior.copy(observedContext=observed))
        }
        val image=f.base.journal.timeline().flatMap {it.operation.orderedMutations}.filterIsInstance<PlanningProfilePut>().last().after
        val service=SyncConflictResolutionService(f.base.mutations,f.base.journal,f.receive,f.base.events,f.base.tasks,f.base.profiles,f.base.academics)
        check(service.resolve(conflict.conflictId,listOf(PlanningProfilePut(image,image.copy(name="Explicit selected title")))) is SyncConflictResolutionResult.Resolved)
        c.refresh();check(c.openConflicts.isEmpty());c.inspect(conflict.conflictId)
        check(c.selected!!.status==SyncConflictStatus.RESOLVED);check(c.selected!!.resolution!=null)
    } }
    @Test fun restartRetainsHistoryAndConflictButDropsPlanBranch() {
        val file=File.createTempFile("d10-restart-",".db")
        var db=openDesktopDatabase(file.absolutePath);val first=D10ProductFixtureGraph(db)
        try {
            runBlocking {first.seed();first.enroll();first.profileConflict()}
            val count=runBlocking {first.base.journal.timeline().size};first.base.client.close();db.close()
            db=openDesktopDatabase(file.absolutePath);val next=D10ProductFixtureGraph(db)
            try {runBlocking {
                val h=next.history();h.refresh();check(h.rows.size==count)
                val s=next.sync();s.refresh();check(s.openConflicts.size==1)
                val p=next.workspace();p.refresh();check(p.preview==null);check(p.draft.referenceNow.isEmpty())
            }} finally {next.base.client.close()}
        } finally {db.close();file.delete()}
    }
    private fun fixture(test:(D10ProductFixtureGraph)->Unit) {
        val file=File.createTempFile("d10-product-",".db");val db=openDesktopDatabase(file.absolutePath);val f=D10ProductFixtureGraph(db)
        try {runBlocking {f.seed()};test(f)} finally {f.base.client.close();db.close();file.delete()}
    }
}

private fun FocusBlock.toSemanticImage() = FocusBlockImage(id.value,taskId.value,
    ZonedTimeRangeImage(time.start.toString(),time.endExclusive.toString(),time.timeZone.id),FlexibilityImage.valueOf(flexibility.name),PinStateImage.valueOf(pinState.name))
