package dev.agenticscheduler.database

import dev.agenticscheduler.application.academic.*
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.id.*
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.sync.*
import dev.agenticscheduler.planner.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.ZERO

class AcademicAuthoringIntegrationTest {
    @Test fun `explicit prerequisite setup creates journaled aggregates without defaults`() = runBlocking { fixture { f ->
        val year = success(f.service.createAcademicYear(yearInput))
        val term = success(f.service.createSemester(termInput(year.id)))
        assertEquals(year, f.repo.getAcademicYear(year.id))
        assertEquals(term, f.repo.getSemester(term.id))
        assertEquals(2, f.journal.timeline().size)
        assertIs<AcademicYearPut>(f.journal.timeline()[0].operation.orderedMutations.single()).also { assertNull(it.before) }
        assertIs<SemesterPut>(f.journal.timeline()[1].operation.orderedMutations.single()).also { assertNull(it.before); assertEquals(2, it.after.academicWeeks.size) }
        val facts = assertIs<ConflictAwareRead.Projected<AcademicAuthoringFacts>>(f.service.loadFacts()).value
        assertTrue(facts.courses.isEmpty() && facts.periodTemplates.isEmpty() && facts.courseScheduleRules.isEmpty() && facts.exams.isEmpty())
    } }

    @Test fun `semester requires a real containing academic year`() = runBlocking { fixture { f ->
        assertIs<AcademicEditingIssue.MissingAcademicYear>(invalid(f.service.createSemester(termInput(AcademicYearId(id(90))))).single())
        val year = success(f.service.createAcademicYear(yearInput.copy(endDateExclusive = LocalDate(2026, 9, 10))))
        assertIs<AcademicEditingIssue.SemesterMembership>(invalid(f.service.createSemester(termInput(year.id))).single())
        assertTrue(f.repo.observeSemesters().first().isEmpty())
        assertEquals(1, f.journal.timeline().size)
    } }

    @Test fun `prerequisite updates journal real before images and preserve aggregate children`() = runBlocking { fixture { f ->
        f.setup()
        val year = assertIs<AcademicEditingResult.Success<AcademicYear>>(f.service.updateAcademicYear(f.year.id, yearInput.copy(name = "Renamed year"), f.year))
        val term = assertIs<AcademicEditingResult.Success<Semester>>(f.service.updateSemester(f.term.id, termInput(f.year.id).copy(name = "Renamed term"), f.term))
        val template = success(f.service.createPeriodTemplate(templateInput))
        val updated = assertIs<AcademicEditingResult.Success<PeriodTemplate>>(f.service.updatePeriodTemplate(template.id, templateInput.copy(name = "Renamed template"), template))
        assertEquals(f.year.name, assertIs<AcademicYearPut>(f.operation(year.mutationId).orderedMutations.single()).before!!.name)
        val termPut = assertIs<SemesterPut>(f.operation(term.mutationId).orderedMutations.single())
        assertEquals(f.term.name, termPut.before!!.name)
        assertEquals(termPut.before!!.academicWeeks, termPut.after.academicWeeks)
        assertEquals(template.name, assertIs<PeriodTemplatePut>(f.operation(updated.mutationId).orderedMutations.single()).before!!.name)
        assertEquals(year.value, f.repo.getAcademicYear(f.year.id))
        assertEquals(term.value, f.repo.getSemester(f.term.id))
        assertEquals(template.periods, f.repo.getPeriodTemplate(template.id)!!.periods)
        for (record in f.journal.timeline()) {
            val encoded = SyncWireCodec.encodePayload(SyncPayloadV1(operation = record.operation))
            assertEquals(record.operation, assertIs<PayloadDecodeResult.Supported>(SyncWireCodec.decodePayload(encoded)).payload.operation)
        }
    } }

    @Test fun `course creation and update preserve source fields identity and actual before image`() = runBlocking { fixture { f ->
        f.setup()
        val created = assertIs<AcademicEditingResult.Success<Course>>(f.service.createCourse(CourseInput(f.term.id, " Course ", null)))
        val course = created.value
        assertEquals(" Course ", course.name)
        assertNull(course.code)
        assertNull(assertIs<CoursePut>(f.operation(created.mutationId).orderedMutations.single()).before)
        val edited = assertIs<AcademicEditingResult.Success<Course>>(f.service.updateCourse(course.id, CourseInput(f.term.id, "Edited", " C-1 "), course))
        val put = assertIs<CoursePut>(f.operation(edited.mutationId).orderedMutations.single())
        assertEquals(course.name, put.before!!.name)
        assertEquals(" C-1 ", put.after.code)
        assertEquals(course.id, edited.value.id)
        assertEquals(edited.value, f.repo.getCourse(course.id))
        assertEquals(1, f.journal.diff(edited.mutationId.value).size)
    } }

    @Test fun `missing semester and invalid fields leave no partial state or causal journal`() = runBlocking { fixture { f ->
        for (input in listOf(CourseInput(SemesterId(id(20)), "Course", null), CourseInput(SemesterId(id(20)), " ", ""))) assertIs<AcademicEditingResult.Invalid>(f.service.createCourse(input))
        assertTrue(f.repo.observeCourses().first().isEmpty())
        assertTrue(f.journal.timeline().isEmpty())
        assertNull(f.journal.localReplicaState())
    } }

    @Test fun `invalid persisted semester membership cannot be used for course creation`() = runBlocking { fixture { f ->
        f.setup()
        // Foreign keys remain valid; an older raw write narrowed the containing year.
        f.repo.upsertAcademicYear(f.year.copy(endDateExclusive = LocalDate(2026, 9, 10)))
        assertIs<AcademicEditingIssue.SemesterMembership>(invalid(f.service.createCourse(CourseInput(f.term.id, "Course", null))).single())
        assertTrue(f.repo.observeCourses().first().isEmpty())
        assertEquals(2, f.journal.timeline().size)
    } }

    @Test fun `clock rules create and update using canonical teaching weeks and stable IDs`() = runBlocking { fixture { f ->
        f.setup()
        val course = f.course()
        val created = assertIs<AcademicEditingResult.Success<CourseScheduleRule>>(f.service.createRule(ruleInput(course.id)))
        val updated = assertIs<AcademicEditingResult.Success<CourseScheduleRule>>(f.service.updateRule(created.value.id, ruleInput(course.id).copy(dayOfWeek = DayOfWeek.THURSDAY, room = "Room")))
        assertEquals(created.value.id, updated.value.id)
        assertEquals(listOf(1, 2), updated.value.teachingWeeks.weeks.map { it.value })
        assertEquals(updated.value, f.repo.getCourseScheduleRule(updated.value.id))
        val put = assertIs<CourseScheduleRulePut>(f.operation(updated.mutationId).orderedMutations.single())
        assertEquals(created.value.dayOfWeek.name, put.before!!.dayOfWeek.name)
        assertEquals("Room", put.after.room)
        assertTrue(f.repo.observePeriodTemplates().first().isEmpty(), "ClockTime must not invent a template.")
    } }

    @Test fun `unknown rule course and unknown teaching week are rejected without writes`() = runBlocking { fixture { f ->
        f.setup()
        assertIs<AcademicEditingIssue.MissingCourse>(invalid(f.service.createRule(ruleInput(CourseId(id(80))))).single())
        val course = f.course()
        val count = f.journal.timeline().size
        val input = ruleInput(course.id).copy(teachingWeeks = TeachingWeekSet.of(listOf(AcademicWeekNumber(3))))
        val issue = assertIs<AcademicEditingIssue.CourseResolution>(invalid(f.service.createRule(input)).single())
        assertIs<CourseSessionResolutionIssue.UnknownAcademicWeek>(issue.issues.single())
        assertTrue(f.repo.observeCourseScheduleRules().first().isEmpty())
        assertEquals(count, f.journal.timeline().size)
    } }

    @Test fun `period based rules require explicit template and endpoint periods`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course()
        val missing = CourseTimeSpec.PeriodBased(PeriodTemplateId(id(91)), AcademicPeriodNumber(1), AcademicPeriodNumber(2))
        assertIs<CourseSessionResolutionIssue.UnknownPeriodTemplate>(assertIs<AcademicEditingIssue.CourseResolution>(invalid(f.service.createRule(ruleInput(course.id).copy(time = missing))).single()).issues.single())
        val template = success(f.service.createPeriodTemplate(templateInput))
        for (time in listOf(CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(2), AcademicPeriodNumber(3)), CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(1), AcademicPeriodNumber(2)))) {
            val issues = assertIs<AcademicEditingIssue.CourseResolution>(invalid(f.service.createRule(ruleInput(course.id).copy(time = time))).single()).issues
            assertTrue(issues.any { it is CourseSessionResolutionIssue.UnknownStartPeriod || it is CourseSessionResolutionIssue.UnknownEndPeriod })
        }
        val rule = success(f.service.createRule(ruleInput(course.id).copy(time = CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(1), AcademicPeriodNumber(3)))))
        assertEquals(rule, f.repo.getCourseScheduleRule(rule.id))
        assertIs<CourseTimeSpec.PeriodBased>(rule.time)
    } }

    @Test fun `empty or invalidly ordered period templates produce typed validation`() = runBlocking { fixture { f ->
        for (input in listOf(templateInput.copy(periods = emptyList<AcademicPeriod>().toImmutableList()), templateInput.copy(periods = templateInput.periods.reversed().toImmutableList()))) assertIs<AcademicEditingResult.Invalid>(f.service.createPeriodTemplate(input))
        assertTrue(f.repo.observePeriodTemplates().first().isEmpty())
        assertNull(f.journal.localReplicaState())
    } }

    @Test fun `unscheduled exam create and edit have proper typed journal before images`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course()
        val created = assertIs<AcademicEditingResult.Success<Exam>>(f.service.createExam(ExamInput(f.term.id, course.id, "Exam", ExamSchedule.Unscheduled)))
        assertNull(assertIs<ExamPut>(f.operation(created.mutationId).orderedMutations.single()).before)
        val updated = assertIs<AcademicEditingResult.Success<Exam>>(f.service.updateExam(created.value.id, ExamInput(f.term.id, null, "Edited", ExamSchedule.Unscheduled), created.value))
        assertIs<ExamSchedule.Unscheduled>(f.repo.getExam(updated.value.id)!!.schedule)
        assertEquals(created.value.courseId!!.value, assertIs<ExamPut>(f.operation(updated.mutationId).orderedMutations.single()).before!!.courseId)
    } }

    @Test fun `DateOnly exam creates updates and round trips without instant coercion`() = runBlocking { fixture { f ->
        f.setup()
        val exam = success(f.service.createExam(ExamInput(f.term.id, null, "Date", ExamSchedule.DateOnly(start))))
        val after = success(f.service.updateExam(exam.id, ExamInput(f.term.id, null, "Date changed", ExamSchedule.DateOnly(LocalDate(2026, 9, 10)))))
        assertEquals(after, f.repo.getExam(exam.id))
        assertIs<ExamSchedule.DateOnly>(after.schedule)
        val put = assertIs<ExamPut>(f.journal.timeline().last().operation.orderedMutations.single())
        assertIs<ExamScheduleImage.DateOnly>(put.before!!.schedule)
        assertIs<ExamScheduleImage.DateOnly>(put.after.schedule)
    } }

    @Test fun `Exact exam creates updates and round trips exact range and zone`() = runBlocking { fixture { f ->
        f.setup()
        val exact = ExamSchedule.Exact(ZonedTimeRange(Instant.parse("2026-09-10T08:00:00.123Z"), Instant.parse("2026-09-10T09:00:00.456Z"), TimeZone.UTC))
        val exam = success(f.service.createExam(ExamInput(f.term.id, null, "Exact", exact)))
        val after = success(f.service.updateExam(exam.id, ExamInput(f.term.id, null, "Edited", exact)))
        assertEquals(after, f.repo.getExam(exam.id))
        assertIs<ExamSchedule.Exact>(after.schedule)
    } }

    @Test fun `exam missing semester missing course and foreign course association fail closed`() = runBlocking { fixture { f ->
        f.setup()
        assertIs<AcademicEditingIssue.MissingSemester>(invalid(f.service.createExam(ExamInput(SemesterId(id(31)), null, "Exam", ExamSchedule.Unscheduled))).single())
        val missing = assertIs<AcademicEditingIssue.ExamValidation>(invalid(f.service.createExam(ExamInput(f.term.id, CourseId(id(32)), "Exam", ExamSchedule.Unscheduled))).single())
        assertEquals(ExamValidationResult.LINKED_COURSE_REQUIRED, missing.result)
        val term = success(f.service.createSemester(termInput(f.year.id).copy(name = "Other")))
        val course = success(f.service.createCourse(CourseInput(term.id, "Other", null)))
        assertEquals(ExamValidationResult.LINKED_COURSE_SEMESTER_MISMATCH, assertIs<AcademicEditingIssue.ExamValidation>(invalid(f.service.createExam(ExamInput(f.term.id, course.id, "Exam", ExamSchedule.Unscheduled))).single()).result)
        assertTrue(f.repo.observeExams().first().isEmpty())
    } }

    @Test fun `exam date and exact schedule validation preserve semester boundaries and timezone`() = runBlocking { fixture { f ->
        f.setup()
        val schedules = listOf(ExamSchedule.DateOnly(end), ExamSchedule.Exact(ZonedTimeRange(Instant.parse("2026-09-10T08:00:00Z"), Instant.parse("2026-09-10T09:00:00Z"), TimeZone.of("Asia/Shanghai"))), ExamSchedule.Exact(ZonedTimeRange(Instant.parse("2026-09-20T23:00:00Z"), Instant.parse("2026-09-21T01:00:00Z"), TimeZone.UTC)))
        for (schedule in schedules) assertIs<AcademicEditingIssue.ExamValidation>(invalid(f.service.createExam(ExamInput(f.term.id, null, "Exam", schedule))).single())
        assertEquals(2, f.journal.timeline().size)
    } }

    @Test fun `missing updates and stale snapshots never allocate history or dots`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course(); val count = f.journal.timeline().size; val state = f.journal.localReplicaState()
        assertIs<AcademicEditingResult.NotFound>(f.service.updateCourse(CourseId(id(92)), CourseInput(f.term.id, "Other", null)))
        assertIs<AcademicEditingResult.Stale>(f.service.updateCourse(course.id, CourseInput(f.term.id, "Other", null), course.copy(name = "Old")))
        assertEquals(count, f.journal.timeline().size); assertEquals(state, f.journal.localReplicaState())
        assertEquals(course, f.repo.getCourse(course.id))
    } }

    @Test fun `generated identity collisions never turn creation into an unjournaled update`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course(); val count = f.journal.timeline().size
        val collision = object : UuidV7Generator { override fun next() = course.id.value }
        val service = f.serviceWith(collision)
        assertIs<AcademicEditingResult.AlreadyExists>(service.createCourse(CourseInput(f.term.id, "Collision", null)))
        assertEquals(course, f.repo.getCourse(course.id)); assertEquals(count, f.journal.timeline().size)
    } }

    @Test fun `production UUIDv7 path creates distinct valid entity and mutation identities`() = runBlocking { fixture { f ->
        val service = f.serviceWith(productionUuidV7Generator())
        val first = assertIs<AcademicEditingResult.Success<AcademicYear>>(service.createAcademicYear(yearInput))
        val second = assertIs<AcademicEditingResult.Success<AcademicYear>>(service.createAcademicYear(yearInput.copy(name = "Other")))
        assertNotEquals(first.value.id, second.value.id)
        for (value in listOf(first.value.id.value, second.value.id.value, first.mutationId.value, second.mutationId.value)) assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(value))
    } }

    @Test fun `semester updates cannot remove weeks used by existing rules`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course(); val rule = success(f.service.createRule(ruleInput(course.id)))
        val count = f.journal.timeline().size
        assertIs<AcademicEditingIssue.CourseResolution>(invalid(f.service.updateSemester(f.term.id, termInput(f.year.id).copy(academicWeeks = listOf(weeks.first()).toImmutableList()))).single())
        assertEquals(f.term, f.repo.getSemester(f.term.id)); assertEquals(rule, f.repo.getCourseScheduleRule(rule.id)); assertEquals(count, f.journal.timeline().size)
    } }

    @Test fun `year update cannot strand a semester outside its date range`() = runBlocking { fixture { f ->
        f.setup(); val count = f.journal.timeline().size
        assertIs<AcademicEditingIssue.SemesterMembership>(invalid(f.service.updateAcademicYear(f.year.id, yearInput.copy(endDateExclusive = LocalDate(2026, 9, 10)))).single())
        assertEquals(f.year, f.repo.getAcademicYear(f.year.id)); assertEquals(count, f.journal.timeline().size)
    } }

    @Test fun `period template update validates dependent rules and preserves periods on rejection`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course(); val template = success(f.service.createPeriodTemplate(templateInput))
        success(f.service.createRule(ruleInput(course.id).copy(time = CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(1), AcademicPeriodNumber(3)))))
        assertIs<AcademicEditingIssue.CourseResolution>(invalid(f.service.updatePeriodTemplate(template.id, templateInput.copy(periods = listOf(templateInput.periods.first()).toImmutableList()))).single())
        assertEquals(template, f.repo.getPeriodTemplate(template.id))
        assertEquals("Renamed", success(f.service.updatePeriodTemplate(template.id, templateInput.copy(name = "Renamed"))).name)
    } }

    @Test fun `course reparenting cannot invalidate an existing linked exam`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course()
        success(f.service.createExam(ExamInput(f.term.id, course.id, "Exam", ExamSchedule.Unscheduled)))
        val other = success(f.service.createSemester(termInput(f.year.id).copy(name = "Other")))
        assertIs<AcademicEditingIssue.ExamValidation>(invalid(f.service.updateCourse(course.id, CourseInput(other.id, "Moved", null))).single())
        assertEquals(course, f.repo.getCourse(course.id))
    } }

    @Test fun `rule update preserves existing exception occurrence identity and rejects orphaning`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course(); val rule = success(f.service.createRule(ruleInput(course.id)))
        val exception = CourseOccurrenceException(CourseOccurrenceExceptionId(id(120)), CourseOccurrenceKey(rule.id, AcademicWeekNumber(2)), CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Clear)
        f.repo.upsertCourseOccurrenceException(exception)
        assertIs<AcademicEditingIssue.CourseResolution>(invalid(f.service.updateRule(rule.id, ruleInput(course.id).copy(teachingWeeks = TeachingWeekSet.of(listOf(AcademicWeekNumber(1)))))).single())
        val after = success(f.service.updateRule(rule.id, ruleInput(course.id).copy(room = "New")))
        assertEquals(rule.id, after.id); assertEquals(exception, f.repo.getCourseOccurrenceException(exception.id))
    } }

    @Test fun `DST ambiguity is reported by the existing resolver rather than guessed`() = runBlocking { fixture { f ->
        val year = success(f.service.createAcademicYear(AcademicYearInput("Year", LocalDate(2026, 1, 1), LocalDate(2027, 1, 1))))
        val term = success(f.service.createSemester(SemesterInput(year.id, "DST", LocalDate(2026, 10, 26), LocalDate(2026, 11, 9), TimeZone.of("America/New_York"), listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 10, 26), LocalDate(2026, 11, 2))).toImmutableList())))
        val course = success(f.service.createCourse(CourseInput(term.id, "DST", null)))
        val issues = assertIs<AcademicEditingIssue.CourseResolution>(invalid(f.service.createRule(ruleInput(course.id).copy(dayOfWeek = DayOfWeek.SUNDAY, teachingWeeks = TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), time = CourseTimeSpec.ClockTime(LocalTime(1, 30), LocalTime(2, 30))))).single()).issues
        assertIs<CourseSessionResolutionIssue.DstTransitionRejected>(issues.single())
    } }

    @Test fun `OPEN conflict blocks normal Course Rule and Exam writes and leaves evidence intact`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course(); val rule = success(f.service.createRule(ruleInput(course.id)))
        val exam = success(f.service.createExam(ExamInput(f.term.id, null, "Exam", ExamSchedule.Unscheduled)))
        val values = f.journal.timeline().map { it.operation.orderedMutations.single() }.filter { it is CoursePut || it is CourseScheduleRulePut || it is ExamPut }
        values.forEachIndexed { index, mutation -> f.conflict(mutation, index) }
        val count = f.journal.timeline().size; val causal = f.journal.localReplicaState()
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.updateCourse(course.id, CourseInput(f.term.id, "Other", null)))
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.updateRule(rule.id, ruleInput(course.id).copy(room = "Other")))
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.updateExam(exam.id, ExamInput(f.term.id, null, "Other", ExamSchedule.Unscheduled)))
        assertEquals(3, f.receive.conflicts(space).count { it.status == SyncConflictStatus.OPEN })
        assertEquals(count, f.journal.timeline().size); assertEquals(causal, f.journal.localReplicaState())
    } }

    @Test fun `OPEN parent conflict prevents authoring from raw durable prerequisite values`() = runBlocking { fixture { f ->
        f.setup(); f.conflict(f.journal.timeline().last().operation.orderedMutations.single())
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.createCourse(CourseInput(f.term.id, "Course", null)))
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.createExam(ExamInput(f.term.id, null, "Exam", ExamSchedule.Unscheduled)))
        assertTrue(f.repo.observeCourses().first().isEmpty()); assertEquals(2, f.journal.timeline().size)
    } }

    @Test fun `OPEN year and template conflicts block setup and dependent rule commands`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course()
        val template = success(f.service.createPeriodTemplate(templateInput))
        f.conflict(f.journal.timeline().first().operation.orderedMutations.single(), 0)
        f.conflict(f.journal.timeline().last().operation.orderedMutations.single(), 1)
        val count = f.journal.timeline().size
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.updateAcademicYear(f.year.id, yearInput.copy(name = "Other")))
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.updatePeriodTemplate(template.id, templateInput.copy(name = "Other")))
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.createRule(ruleInput(course.id).copy(time = CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(1), AcademicPeriodNumber(3)))))
        assertTrue(f.repo.observeCourseScheduleRules().first().isEmpty())
        assertEquals(count, f.journal.timeline().size)
    } }

    @Test fun `unrelated legacy invalid course and OPEN conflict do not invalidate other authoring`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course()
        f.conflict(f.journal.timeline().last().operation.orderedMutations.single())
        val legacy = Course(CourseId(id(100)), f.term.id, "Legacy invalid schedule", null)
        f.repo.upsertCourse(legacy)
        val legacyRule = CourseScheduleRule(CourseScheduleRuleId(id(101)), legacy.id, DayOfWeek.MONDAY,
            TeachingWeekSet.of(listOf(AcademicWeekNumber(3))), CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)), null)
        f.repo.upsertCourseScheduleRule(legacyRule)
        val other = success(f.service.createCourse(CourseInput(f.term.id, "Independent", null)))
        assertEquals(other, f.repo.getCourse(other.id))
        assertEquals(course, f.repo.getCourse(course.id)); assertEquals(legacy, f.repo.getCourse(legacy.id))
        assertEquals(legacyRule, f.repo.getCourseScheduleRule(legacyRule.id))
        assertEquals(1, f.receive.conflicts(space).size)
    } }

    @Test fun `OPEN template blocks PeriodBased rule but independent ClockTime remains usable`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course()
        val template = success(f.service.createPeriodTemplate(templateInput))
        f.conflict(f.journal.timeline().last().operation.orderedMutations.single())
        assertIs<AcademicEditingResult.BlockedBySyncConflict>(f.service.createRule(ruleInput(course.id).copy(
            time = CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(1), AcademicPeriodNumber(3)),
        )))
        val clockRule = success(f.service.createRule(ruleInput(course.id)))
        assertEquals(listOf(clockRule), f.repo.observeCourseScheduleRules().first())
        assertEquals(template, f.repo.getPeriodTemplate(template.id))
        assertEquals(1, f.receive.conflicts(space).size)
    } }

    @Test fun `frontend query projects OPEN conflicts with metadata and does not mutate facts`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course(); val original = f.journal.timeline().last().operation.orderedMutations.single()
        f.conflict(original, provisional = (original as CoursePut).copy(after = original.after.copy(name = "Provisional")))
        val projected = assertIs<ConflictAwareRead.Projected<AcademicAuthoringFacts>>(f.service.loadFacts())
        assertEquals("Provisional", projected.value.courses.single().name)
        assertEquals(course, f.repo.getCourse(course.id)); assertEquals(1, projected.syncConflictRefs.size)
    } }

    @Test fun `frontend query fails closed when a provisional candidate cannot be decoded`() = runBlocking { fixture { f ->
        f.setup(); f.course(); val conflict = f.conflict(f.journal.timeline().last().operation.orderedMutations.single())
        f.receive.saveConflict(conflict.copy(participants = conflict.participants.map { it.copy(candidateValuesJson = "{}") }))
        assertIs<ConflictAwareRead.Unprojectable>(f.service.loadFacts())
    } }

    @Test fun `journal failure rolls back raw aggregate children history and causal state together`() = runBlocking { fixture { f ->
        f.setup(); val count = f.journal.timeline().size; val causal = f.journal.localReplicaState()
        val failing = object : MutationJournalRepository by f.journal {
            override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) { f.journal.saveLocalReplicaState(state); error("injected commit failure") }
        }
        val service = f.serviceWith(SequenceIds(500), failing)
        assertFailsWith<IllegalStateException> { service.updateSemester(f.term.id, termInput(f.year.id).copy(name = "Changed", academicWeeks = listOf(weeks.first()).toImmutableList())) }
        assertEquals(f.term, f.repo.getSemester(f.term.id)); assertEquals(count, f.journal.timeline().size); assertEquals(causal, f.journal.localReplicaState())
        assertEquals(1, f.journal.entityChanges(EntityKind.SEMESTER, f.term.id.value).size)
    } }

    @Test fun `restart retains authored graph exact exam and complete journal`() = runBlocking {
        val path = Files.createTempFile("academic-authoring", ".db")
        try {
            val db = openDesktopDatabase(path.toAbsolutePath().toString()); val f = Fixture(db)
            f.setup(); val course = f.course(); val rule = success(f.service.createRule(ruleInput(course.id)))
            val exam = success(f.service.createExam(ExamInput(f.term.id, course.id, "Exam", ExamSchedule.DateOnly(start))))
            val count = f.journal.timeline().size; db.close()
            val reopened = Fixture(openDesktopDatabase(path.toAbsolutePath().toString()))
            try {
                assertEquals(course, reopened.repo.getCourse(course.id)); assertEquals(rule, reopened.repo.getCourseScheduleRule(rule.id)); assertEquals(exam, reopened.repo.getExam(exam.id))
                assertEquals(count, reopened.journal.timeline().size)
                assertEquals(2, assertIs<ConflictAwareRead.Projected<AcademicAuthoringFacts>>(reopened.service.loadFacts()).value.semesters.single().academicWeeks.size)
            } finally { reopened.db.close() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun `existing V1 codec and authenticated receive apply authored academic mutations unchanged`() = runBlocking { fixture { source -> fixture { destination ->
        source.setup(); val course = source.course(); val template = success(source.service.createPeriodTemplate(templateInput))
        success(source.service.createRule(ruleInput(course.id).copy(time = CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(1), AcademicPeriodNumber(3)))))
        success(source.service.createExam(ExamInput(source.term.id, course.id, "Date", ExamSchedule.DateOnly(start))))
        val engine = SyncEngine(RoomApplicationTransactionRunner(destination.db), destination.journal, destination.journal, destination.receive, RoomEventRepository(destination.db), RoomTaskRepository(destination.db), RoomPlanningProfileRepository(destination.db), destination.repo, SequenceIds(2000), MutationWallClock { 1 })
        for ((index, record) in source.journal.timeline().withIndex()) {
            val payload = SyncWireCodec.encodePayload(SyncPayloadV1(operation = record.operation))
            assertEquals(record.operation, assertIs<PayloadDecodeResult.Supported>(SyncWireCodec.decodePayload(payload)).payload.operation)
            assertIs<SyncReceiveResult.Applied>(engine.receive(DecryptedPayloadReceipt(space, record.operation.mutationId, index.toLong() + 1, payload)))
        }
        assertEquals(assertIs<ConflictAwareRead.Projected<AcademicAuthoringFacts>>(source.service.loadFacts()).value, assertIs<ConflictAwareRead.Projected<AcademicAuthoringFacts>>(destination.service.loadFacts()).value)
        val projection = RepositoryCalendarQueryService(RoomEventRepository(destination.db), RoomTaskRepository(destination.db), destination.repo).observe(CalendarViewport(start, end, TimeZone.UTC)).first()
        assertTrue(projection.issues.isEmpty()); assertEquals(2, projection.items.count { it.source is CalendarSourceRef.CourseSession }); assertEquals(1, projection.items.count { it is CalendarItem.DateOnly })
    } } }

    @Test fun `authored academic facts feed existing Planner without storing derived sessions`() = runBlocking { fixture { f ->
        f.setup(); val course = f.course()
        success(f.service.createRule(ruleInput(course.id)))
        success(f.service.createExam(ExamInput(f.term.id, course.id, "Exact", ExamSchedule.Exact(ZonedTimeRange(
            Instant.parse("2026-09-07T09:00:00Z"), Instant.parse("2026-09-07T10:00:00Z"), TimeZone.UTC,
        )))))
        val tasks = RoomTaskRepository(f.db)
        val profiles = RoomPlanningProfileRepository(f.db)
        val task = Task(TaskId(id(700)), "Read", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(1.hours, ZERO, 1.hours), null)
        tasks.upsertTask(task)
        val profile = PlanningProfile(PlanningProfileId(id(701)), "Explicit test profile", PlanningProfileConfiguration.Configured(
            TimeZone.UTC, listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(8, 0), LocalTime(12, 0))).toImmutableList(),
            30.minutes, 1.hours, 2.hours, AllDayEventPolicy.NON_BLOCKING,
        ))
        profiles.upsert(profile)
        val ids = SequenceIds(3000)
        val planner = DogfoodPlannerService(tasks, RoomEventRepository(f.db), profiles, f.repo, ids,
            mutations = MutationCoordinator(RoomApplicationTransactionRunner(f.db), f.journal, ids, MutationWallClock { 1 }),
            conflictWritePolicy = SyncConflictWriteGuard(f.receive, space),
            sourceFacts = ActiveConflictAwareSourceFactQuery(ConflictAwareProjection(f.receive), space))
        val now = Instant.parse("2026-09-07T08:00:00Z")
        val preview = assertIs<PlannerPreview.Applicable>(planner.fullReplan(profile.id, now, PlanningHorizon(now, Instant.parse("2026-09-07T12:00:00Z"))))
        val draft = assertIs<FocusBlockMutation.Create>(preview.branch.mutations.single()).draft
        assertEquals(task.id, draft.taskId)
        assertEquals(Instant.parse("2026-09-07T10:00:00Z"), draft.time.start)
        assertTrue(tasks.observeFocusBlocks().first().isEmpty(), "Preview never activates a plan.")
        assertTrue(RoomEventRepository(f.db).observeAll().first().isEmpty(), "CourseSession is not a stored Event.")
        assertEquals(5, f.journal.timeline().size, "Resolving sessions/planning does not author academic facts.")
    } }

    private class SequenceIds(private var next: Int = 1) : UuidV7Generator { override fun next() = id(next++) }
    private class Fixture(val db: AgenticSchedulerDatabase) {
        val repo = RoomAcademicRepository(db)
        val journal = RoomMutationJournalRepository(db)
        val receive = RoomSyncReceiveRepository(db)
        private val ids = SequenceIds()
        val service = serviceWith(ids)
        lateinit var year: AcademicYear
        lateinit var term: Semester
        fun serviceWith(generator: UuidV7Generator, mutationJournal: MutationJournalRepository = journal) = AcademicAuthoringService(repo, generator, MutationCoordinator(RoomApplicationTransactionRunner(db), mutationJournal, generator, MutationWallClock { 1 }), SyncConflictWriteGuard(receive, space), ActiveConflictAwareSourceFactQuery(ConflictAwareProjection(receive), space))
        suspend fun setup() { year = success(service.createAcademicYear(yearInput)); term = success(service.createSemester(termInput(year.id))) }
        suspend fun course() = success(service.createCourse(CourseInput(term.id, "Course", null)))
        suspend fun operation(id: MutationId) = journal.mutation(id.value)!!.operation
        suspend fun conflict(mutation: EntityMutation, index: Int = 0, provisional: EntityMutation? = null): SyncConflict {
            fun operation(value: EntityMutation, n: Int) = SyncOperation(id(n), DvvSnapshot(emptyList(), DotSnapshot(id(n + 1), 0)), HlcSnapshot(1, 0, id(n + 1)), MutationOrigin.User, listOf(value))
            val unequal = provisional ?: when (mutation) {
                is CoursePut -> mutation.copy(after = mutation.after.copy(name = "Conflicting course"))
                is CourseScheduleRulePut -> mutation.copy(after = mutation.after.copy(room = "Conflicting room"))
                is ExamPut -> mutation.copy(after = mutation.after.copy(title = "Conflicting exam"))
                is SemesterPut -> mutation.copy(after = mutation.after.copy(name = "Conflicting semester"))
                is AcademicYearPut -> mutation.copy(after = mutation.after.copy(name = "Conflicting year"))
                is PeriodTemplatePut -> mutation.copy(after = mutation.after.copy(name = "Conflicting template"))
                else -> error("Unsupported test conflict aggregate")
            }
            val left = operation(unequal, 10000 + index * 10); val right = operation(mutation, 10002 + index * 10)
            val participants = listOf(left, right).map { SyncConflictParticipant(MutationId(it.mutationId), it.dvv, LocalJournalCodec.encode(it)) }
            val groups = when (mutation) {
                is ExamPut -> listOf("title")
                is CoursePut, is CourseScheduleRulePut, is SemesterPut, is AcademicYearPut, is PeriodTemplatePut -> listOf("whole")
                else -> error("Unsupported test conflict aggregate")
            }
            val conflict = SyncConflict(id(11000 + index), space, listOf(SyncConflictEntityRef(mutation.entityKind, mutation.entityId, groups)), participants, MutationId(left.mutationId), SyncConflictKind.SEMANTIC, commonCausalContextOf(participants), SyncConflictStatus.OPEN)
            receive.saveConflict(conflict); return conflict
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) { val f = Fixture(openInMemoryDesktopDatabase()); try { block(f) } finally { f.db.close() } }
    private fun invalid(result: AcademicEditingResult<*>) = assertIs<AcademicEditingResult.Invalid>(result).issues
    companion object {
        private val space = SyncSpaceId(id(9000))
        private val start = LocalDate(2026, 9, 7)
        private val end = LocalDate(2026, 9, 21)
        private val weeks = listOf(AcademicWeek(AcademicWeekNumber(1), start, LocalDate(2026, 9, 14)), AcademicWeek(AcademicWeekNumber(2), LocalDate(2026, 9, 14), end)).toImmutableList()
        private val yearInput = AcademicYearInput("Year", LocalDate(2026, 1, 1), LocalDate(2027, 1, 1))
        private fun termInput(year: AcademicYearId) = SemesterInput(year, "Term", start, end, TimeZone.UTC, weeks)
        private val templateInput = PeriodTemplateInput("Periods", listOf(AcademicPeriod(AcademicPeriodNumber(1), LocalTime(8, 0), LocalTime(8, 45)), AcademicPeriod(AcademicPeriodNumber(3), LocalTime(9, 0), LocalTime(9, 45))).toImmutableList())
        private fun ruleInput(course: CourseId) = CourseScheduleRuleInput(course, DayOfWeek.MONDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(2), AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)), null)
        private fun id(n: Int) = "01900000-0000-7000-8000-${n.toString(16).padStart(12, '0')}"
        private fun <T> success(value: AcademicEditingResult<T>): T = assertIs<AcademicEditingResult.Success<T>>(value).value
    }
}
