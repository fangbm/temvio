package dev.agenticscheduler.fixtures

import dev.agenticscheduler.application.academic.*
import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/** Separate D10-02 fixture; D10-01 historical capture inputs are not rewritten. */
class D10CoreFixtureGraph(db: AgenticSchedulerDatabase) {
    val base = D10FixtureGraph(db)
    val authoring = AcademicAuthoringService(base.academics, base.ids, base.mutations, NoActiveSyncSpaceWritePolicy, NoActiveSyncSpaceSourceFactQuery)
    val now = Instant.parse("2026-10-06T08:00:00Z")
    lateinit var year: AcademicYear
    lateinit var semester: Semester
    lateinit var template: PeriodTemplate
    lateinit var course: Course
    lateinit var clockRule: CourseScheduleRule
    lateinit var exam: Exam
    suspend fun seed() {
        base.seed()
        year = authoring.createAcademicYear(AcademicYearInput("2026–27 academic year", LocalDate(2026,9,1), LocalDate(2027,9,1))).value()
        semester = authoring.createSemester(SemesterInput(year.id, "Autumn semester", LocalDate(2026,9,28), LocalDate(2026,12,28), base.zone,
            listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026,9,28), LocalDate(2026,10,5)), AcademicWeek(AcademicWeekNumber(2), LocalDate(2026,10,5), LocalDate(2026,10,12)),
                AcademicWeek(AcademicWeekNumber(3), LocalDate(2026,10,19), LocalDate(2026,10,26))).toImmutableList())).value()
        template = authoring.createPeriodTemplate(PeriodTemplateInput("Campus periods", listOf(AcademicPeriod(AcademicPeriodNumber(1), LocalTime(8,30), LocalTime(9,15)), AcademicPeriod(AcademicPeriodNumber(3), LocalTime(10,0), LocalTime(10,45))).toImmutableList())).value()
        course = authoring.createCourse(CourseInput(semester.id, "Distributed systems", "CS-402")).value()
        clockRule = authoring.createRule(CourseScheduleRuleInput(course.id, DayOfWeek.TUESDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(1), AcademicWeekNumber(2))), CourseTimeSpec.ClockTime(LocalTime(10,0), LocalTime(11,0)), "Lab 4")).value()
        authoring.createRule(CourseScheduleRuleInput(course.id, DayOfWeek.THURSDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(2))), CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(1), AcademicPeriodNumber(3)), null)).value()
        exam = authoring.createExam(ExamInput(semester.id, course.id, "Systems exam", ExamSchedule.DateOnly(base.date))).value()
        authoring.createExam(ExamInput(semester.id, null, "Unscheduled assessment", ExamSchedule.Unscheduled)).value()
        authoring.createExam(ExamInput(semester.id, course.id, "Exact assessment", ExamSchedule.Exact(ZonedTimeRange(Instant.parse("2026-10-08T13:00:00Z"), Instant.parse("2026-10-08T14:00:00Z"), base.zone)))).value()
        base.eventEditor.create(CreateEventInput("Research workshop · a long synthetic title with readable overflow", EventTimeInput.AllDay(LocalDate(2026,10,5), LocalDate(2026,10,9)), Flexibility.HARD, PinState.UNPINNED))
        base.eventEditor.create(CreateEventInput("Cross-midnight Zoned review", EventTimeInput.Zoned(LocalDateTime(2026,10,6,23,0), LocalDateTime(2026,10,7,1,0), base.zone), Flexibility.HARD, PinState.UNPINNED))
        base.eventEditor.create(CreateEventInput("Floating night reading", EventTimeInput.Floating(LocalDateTime(2026,10,6,23,30), LocalDateTime(2026,10,7,0,30)), Flexibility.SOFT, PinState.UNPINNED))
        val task = base.tasks.observeTasks().first().single()
        val focus = FocusBlock(FocusBlockId(base.ids.next()), task.id, ZonedTimeRange(Instant.parse("2026-10-06T09:45:00Z"), Instant.parse("2026-10-06T10:15:00Z"), base.zone), Flexibility.FLEXIBLE, PinState.UNPINNED)
        // Synthetic planned source fact through the same atomic journal seam; no Task completion.
        base.mutations.execute(MutationOrigin.User) { base.tasks.upsertFocusBlock(focus); record(FocusBlockPut(null, FocusBlockImage(focus.id.value, focus.taskId.value, ZonedTimeRangeImage(focus.time.start.toString(), focus.time.endExclusive.toString(), focus.time.timeZone.id), FlexibilityImage.FLEXIBLE, PinStateImage.UNPINNED))) }
    }
}
private fun <T> AcademicEditingResult<T>.value(): T = (this as AcademicEditingResult.Success).value
