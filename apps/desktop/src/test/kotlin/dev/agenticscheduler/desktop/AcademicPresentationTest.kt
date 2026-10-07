package dev.agenticscheduler.desktop

import dev.agenticscheduler.application.academic.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.fixtures.D10CoreFixtureGraph
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.datetime.*
import org.junit.Test

class AcademicPresentationTest {
    @Test fun allSixTypedEditFormsCommitThroughApplicationWithActualBeforeImages() = fixture { f, c ->
        val records = listOf(AcademicRecord.Year(f.year), AcademicRecord.Term(f.semester), AcademicRecord.Template(f.template), AcademicRecord.Subject(f.course), AcademicRecord.Rule(f.clockRule), AcademicRecord.Assessment(f.exam))
        val count = f.base.journal.timeline().size
        records.forEach { record -> c.edit(record); c.draft = c.draft!!.let { if (record.kind == AcademicKind.RULE) it.copy(room = "Changed room") else it.copy(name = record.title + " edited") }; c.save(); check(c.draft == null); check(c.lastCommittedMutationId != null) }
        val added = f.base.journal.timeline().drop(count)
        check(added.size == 6); check(added.map { it.operation.mutationId }.distinct().size == 6)
        check(added.all { it.operation.origin == MutationOrigin.User && it.operation.orderedMutations.size == 1 })
        check((added[3].operation.orderedMutations.single() as CoursePut).before?.name == f.course.name)
    }
    @Test fun explicitYearCreateHasNoSynthesizedSemester() = fixture { f, c ->
        val terms = c.facts!!.semesters
        c.create(AcademicKind.YEAR); c.draft = c.draft!!.copy(name = "Explicit Year", start = "2027-09-01", end = "2028-09-01"); c.save()
        check(c.selected is AcademicRecord.Year); check(c.facts!!.semesters == terms)
    }
    @Test fun semesterRequiresExplicitWeeksAndShowsInvalidResultWithoutWrites() = fixture { f, c ->
        val count = f.base.journal.timeline().size
        c.create(AcademicKind.SEMESTER); c.draft = c.draft!!.copy(name = "Term", yearId = f.year.id, start = "2026-10-01", end = "2026-12-01", zone = "UTC")
        c.save(); check(c.feedback == AcademicFeedback.INVALID); check(c.draft != null)
        c.draft = c.draft!!.copy(weeks = listOf(WeekDraft("1","2026-10-05","2026-10-11")))
        c.save(); check(c.feedback == AcademicFeedback.INVALID); check(f.base.journal.timeline().size == count)
        c.draft = c.draft!!.copy(weeks = listOf(WeekDraft("1","2026-10-05","2026-10-12")))
        c.save(); check(c.selected is AcademicRecord.Term); check(f.base.journal.timeline().size == count + 1)
    }
    @Test fun periodRowsPreserveGapsAndRejectOutOfOrderWithoutRepair() = fixture { f, c ->
        c.create(AcademicKind.TEMPLATE); c.draft = c.draft!!.copy(name = "Explicit", periods = listOf(PeriodDraft("3","10:00","10:45"),PeriodDraft("1","08:30","09:15")))
        val count = f.base.journal.timeline().size; c.save(); check(c.feedback == AcademicFeedback.INVALID); check(f.base.journal.timeline().size == count)
        c.draft = c.draft!!.copy(periods = c.draft!!.periods.reversed()); c.save()
        check((c.selected as AcademicRecord.Template).value.periods.map { it.number.value } == listOf(1,3))
    }
    @Test fun courseAndRuleAreSeparateExplicitCommits() = fixture { f, c ->
        val count = f.base.journal.timeline().size; val rules = c.facts!!.courseScheduleRules.size
        c.create(AcademicKind.COURSE); c.draft = c.draft!!.copy(name = "New Course", semesterId = f.semester.id); c.save()
        val course = (c.selected as AcademicRecord.Subject).value
        check(c.facts!!.courseScheduleRules.size == rules); check(f.base.journal.timeline().size == count + 1)
        c.create(AcademicKind.RULE,course.id); c.draft = c.draft!!.copy(weekday = DayOfWeek.WEDNESDAY,teachingWeeks = "2",ruleTime = RuleTimeChoice.CLOCK_TIME,start = "14:00",end = "15:00")
        c.save(); check(c.selected is AcademicRecord.Rule); check(f.base.journal.timeline().size == count + 2)
        check(f.base.journal.timeline().takeLast(2).map { it.operation.mutationId }.distinct().size == 2)
    }
    @Test fun periodBasedRequiresExistingSelectionAndTeachingWeeksAreValidated() = fixture { f, c ->
        val count = f.base.journal.timeline().size
        c.create(AcademicKind.RULE,f.course.id); c.draft = c.draft!!.copy(weekday = DayOfWeek.MONDAY,teachingWeeks = "2",ruleTime = RuleTimeChoice.PERIOD_BASED,start = "1",end = "3")
        c.save(); check(c.feedback == AcademicFeedback.INVALID)
        c.draft = c.draft!!.copy(templateId = f.template.id,teachingWeeks = "99"); c.save(); check(c.feedback == AcademicFeedback.INVALID)
        check(f.base.journal.timeline().size == count)
        c.draft = c.draft!!.copy(teachingWeeks = "2"); c.save(); check(c.selected is AcademicRecord.Rule)
    }
    @Test fun allExamScheduleStatesAreExplicitAndDateOnlyRemainsDateOnly() = fixture { f, c ->
        for (choice in ExamScheduleChoice.entries) {
            c.create(AcademicKind.EXAM)
            c.draft = c.draft!!.copy(name = "Explicit $choice",semesterId = f.semester.id,examSchedule = choice,
                start = if (choice == ExamScheduleChoice.DATE_ONLY) "2026-10-06" else "2026-10-06T14:00",end = "2026-10-06T15:00")
            c.save(); check(c.draft == null)
            val schedule = (c.selected as AcademicRecord.Assessment).value.schedule
            check(when (choice) { ExamScheduleChoice.UNSCHEDULED -> schedule == ExamSchedule.Unscheduled; ExamScheduleChoice.DATE_ONLY -> schedule is ExamSchedule.DateOnly; ExamScheduleChoice.EXACT -> schedule is ExamSchedule.Exact })
        }
        val p = f.base.reads.observe(calendarViewport(f.base.date,CalendarMode.DAY,f.base.zone)).first()
        check(p.items.none { it.title == "Explicit UNSCHEDULED" }); check(p.items.any { it.title == "Explicit DATE_ONLY" && it is dev.agenticscheduler.application.calendar.CalendarItem.DateOnly })
    }
    @Test fun linkedExamCourseMustBelongToSelectedSemester() = fixture { f, c ->
        val other = (f.authoring.createSemester(SemesterInput(f.year.id,"Other",f.semester.startDate,f.semester.endDateExclusive,f.semester.timeZone,f.semester.academicWeeks)) as AcademicEditingResult.Success).value
        c.refresh(); c.create(AcademicKind.EXAM); c.draft = c.draft!!.copy(name = "Mismatch",semesterId = other.id,courseId = f.course.id,examSchedule = ExamScheduleChoice.UNSCHEDULED)
        val count = f.base.journal.timeline().size; c.save(); check(c.feedback == AcademicFeedback.INVALID); check(c.feedbackDetail!!.contains("LINKED_COURSE_SEMESTER_MISMATCH")); check(f.base.journal.timeline().size == count)
    }
    @Test fun exactExamRejectsDstAmbiguityWithoutWriting() = fixture { f, c ->
        val term = (f.authoring.createSemester(SemesterInput(f.year.id,"NY",LocalDate(2026,10,26),LocalDate(2026,11,9),TimeZone.of("America/New_York"),listOf(AcademicWeek(AcademicWeekNumber(1),LocalDate(2026,10,26),LocalDate(2026,11,2))).toImmutableList())) as AcademicEditingResult.Success).value
        c.refresh(); c.create(AcademicKind.EXAM); c.draft = c.draft!!.copy(name = "DST",semesterId = term.id,examSchedule = ExamScheduleChoice.EXACT,start = "2026-11-01T01:30",end = "2026-11-01T02:30")
        val count = f.base.journal.timeline().size; c.save(); check(c.feedback == AcademicFeedback.INVALID); check(f.base.journal.timeline().size == count)
    }
    @Test fun staleRetainsDraftUntilExplicitRefreshAndReload() = fixture { f, c ->
        c.edit(AcademicRecord.Subject(f.course)); c.draft = c.draft!!.copy(name = "Useful unsaved text")
        f.authoring.updateCourse(f.course.id,CourseInput(f.semester.id,"Concurrent local edit",f.course.code),f.course)
        val count = f.base.journal.timeline().size; c.save(); check(c.feedback == AcademicFeedback.STALE); check(c.draft!!.name == "Useful unsaved text"); check(f.base.journal.timeline().size == count)
        c.refresh(); check(c.draft!!.name == "Useful unsaved text"); c.reloadDraftFromFacts(); check(c.draft!!.name == "Concurrent local edit")
    }
    @Test fun parentConflictBlocksAuthoringAndProjectedRefsRemainVisible() = conflictFixture { f, c, receive, conflict ->
        c.refresh(); val read = c.read as ConflictAwareRead.Projected
        check(read.syncConflictRefs.single().entityKind == EntityKind.SEMESTER)
        c.create(AcademicKind.COURSE); c.draft = c.draft!!.copy(name = "Blocked",semesterId = f.semester.id)
        val count = f.base.journal.timeline().size; c.save(); check(c.feedback == AcademicFeedback.SYNC_CONFLICT)
        check(f.base.journal.timeline().size == count); check(receive.conflicts(space).single() == conflict)
    }
    @Test fun malformedConflictFailsClosedWithoutRepositoryFallback() = conflictFixture { f, c, receive, conflict ->
        receive.saveConflict(conflict.copy(participants = conflict.participants.map { it.copy(candidateValuesJson = "{}") }))
        c.refresh(); check(c.read is ConflictAwareRead.Unprojectable); check(c.facts == null)
        c.create(AcademicKind.COURSE); c.draft = c.draft!!.copy(name = "Forbidden raw fallback",semesterId = f.semester.id)
        val count = f.base.journal.timeline().size; c.save(); check(f.base.journal.timeline().size == count); check(c.draft != null)
    }
    @Test fun filteringAndPlannedFocusBlocksNeverCompleteTaskOrWrite() = fixture { f, _ ->
        val tasks = f.base.tasks.observeTasks().first(); val count = f.base.journal.timeline().size
        check(tasks.single().status == dev.agenticscheduler.domain.task.TaskStatus.OPEN)
        TaskFilter.entries.forEach { filteredTasks(tasks,it) }; check(f.base.journal.timeline().size == count)
        check(f.base.tasks.observeFocusBlocks().first().isNotEmpty()); check(f.base.tasks.observeTasks().first() == tasks)
    }
    @Test fun creationIdCollisionIsReportedWithoutJournalOrCausalAdvance() = fixture { f, _ ->
        val ids = object : dev.agenticscheduler.application.id.UuidV7Generator { override fun next() = f.year.id.value }
        val service = AcademicAuthoringService(f.base.academics,ids,f.base.mutations,NoActiveSyncSpaceWritePolicy,NoActiveSyncSpaceSourceFactQuery)
        val c = AcademicScreenCoordinator(service,f.base.eventEditor); c.refresh(); c.create(AcademicKind.YEAR)
        c.draft = c.draft!!.copy(name="Collision",start="2027-09-01",end="2028-09-01")
        val count=f.base.journal.timeline().size; val causal=f.base.journal.localReplicaState()
        c.save(); check(c.feedback==AcademicFeedback.ALREADY_EXISTS); check(c.draft!=null)
        check(f.base.journal.timeline().size==count); check(f.base.journal.localReplicaState()==causal)
    }

    private fun fixture(test: suspend (D10CoreFixtureGraph,AcademicScreenCoordinator) -> Unit) = runBlocking {
        val file = java.io.File.createTempFile("d10-core-", ".db"); val db = openDesktopDatabase(file.absolutePath); val f = D10CoreFixtureGraph(db)
        try { f.seed(); val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor); c.refresh(); test(f,c) } finally { f.base.client.close(); db.close(); file.delete() }
    }
    private fun conflictFixture(test: suspend (D10CoreFixtureGraph,AcademicScreenCoordinator,RoomSyncReceiveRepository,SyncConflict) -> Unit) = runBlocking {
        val file = java.io.File.createTempFile("d10-core-", ".db"); val db = openDesktopDatabase(file.absolutePath); val f = D10CoreFixtureGraph(db)
        try {
            f.seed(); val receive = RoomSyncReceiveRepository(db)
            val mutation = f.base.journal.timeline().map { it.operation.orderedMutations.single() }.filterIsInstance<SemesterPut>().single()
            fun op(value: EntityMutation,n: Int) = SyncOperation(id(n),DvvSnapshot(emptyList(),DotSnapshot(id(n+1),0)),HlcSnapshot(1,0,id(n+1)),MutationOrigin.User,listOf(value))
            val left = op(mutation.copy(after = mutation.after.copy(name = "Provisional semester")),9001); val right = op(mutation,9003)
            val participants = listOf(left,right).map { SyncConflictParticipant(MutationId(it.mutationId),it.dvv,LocalJournalCodec.encode(it)) }
            val conflict = SyncConflict(id(9100),space,listOf(SyncConflictEntityRef(EntityKind.SEMESTER,mutation.entityId,listOf("whole"))),participants,MutationId(left.mutationId),SyncConflictKind.SEMANTIC,commonCausalContextOf(participants),SyncConflictStatus.OPEN)
            receive.saveConflict(conflict)
            val service = AcademicAuthoringService(f.base.academics,f.base.ids,f.base.mutations,SyncConflictWriteGuard(receive,space),ActiveConflictAwareSourceFactQuery(ConflictAwareProjection(receive),space))
            test(f,AcademicScreenCoordinator(service,f.base.eventEditor),receive,conflict)
        } finally { f.base.client.close(); db.close(); file.delete() }
    }
    companion object { private fun id(n: Int) = "01900000-0000-7000-8000-${n.toString().padStart(12,'0')}"; private val space = SyncSpaceId(id(9000)) }
}
