package dev.agenticscheduler.application.academic

import dev.agenticscheduler.application.history.SyncConflictWriteBlock
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.sync.MutationId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Parse-independent source inputs. No default academic facts are synthesized. */
data class AcademicYearInput(val name: String, val startDate: LocalDate, val endDateExclusive: LocalDate)
data class SemesterInput(
    val academicYearId: AcademicYearId,
    val name: String,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val timeZone: TimeZone,
    val academicWeeks: ImmutableList<AcademicWeek>,
)
data class PeriodTemplateInput(val name: String, val periods: ImmutableList<AcademicPeriod>)
data class CourseInput(val semesterId: SemesterId, val name: String, val code: String?)
data class CourseScheduleRuleInput(
    val courseId: CourseId,
    val dayOfWeek: DayOfWeek,
    val teachingWeeks: TeachingWeekSet,
    val time: CourseTimeSpec,
    val room: String?,
)
data class ExamInput(val semesterId: SemesterId, val courseId: CourseId?, val title: String, val schedule: ExamSchedule)

sealed interface AcademicEditingResult<out T> {
    data class Success<T>(val value: T, val mutationId: MutationId) : AcademicEditingResult<T>
    data class Invalid(val issues: ImmutableList<AcademicEditingIssue>) : AcademicEditingResult<Nothing>
    data object NotFound : AcademicEditingResult<Nothing>
    data object AlreadyExists : AcademicEditingResult<Nothing>
    data object Stale : AcademicEditingResult<Nothing>
    data class BlockedBySyncConflict(val blocks: ImmutableList<SyncConflictWriteBlock>) : AcademicEditingResult<Nothing>
}

enum class AcademicInputField { NAME, COURSE_CODE, ROOM, DATE_RANGE, ACADEMIC_WEEKS, PERIODS }

sealed interface AcademicEditingIssue {
    data class InvalidField(val field: AcademicInputField) : AcademicEditingIssue
    data class MissingAcademicYear(val id: AcademicYearId) : AcademicEditingIssue
    data class MissingSemester(val id: SemesterId) : AcademicEditingIssue
    data class MissingCourse(val id: CourseId) : AcademicEditingIssue
    data class SemesterMembership(val semesterId: SemesterId, val result: SemesterAcademicYearValidationResult) : AcademicEditingIssue
    data class CourseResolution(val courseId: CourseId, val issues: ImmutableList<CourseSessionResolutionIssue>) : AcademicEditingIssue
    data class ExamValidation(val examId: ExamId, val result: ExamValidationResult) : AcademicEditingIssue
    data class HolidayOutsideSemester(val holidayId: AcademicHolidayId) : AcademicEditingIssue
}

/** Frontend-readable Domain facts; source-conflict metadata is supplied by the existing read boundary. */
data class AcademicAuthoringFacts(
    val academicYears: ImmutableList<AcademicYear>,
    val semesters: ImmutableList<Semester>,
    val courses: ImmutableList<Course>,
    val periodTemplates: ImmutableList<PeriodTemplate>,
    val courseScheduleRules: ImmutableList<CourseScheduleRule>,
    val exams: ImmutableList<Exam>,
)

internal fun academicFields(input: Any): List<AcademicEditingIssue> = buildList {
    fun invalid(field: AcademicInputField) { add(AcademicEditingIssue.InvalidField(field)) }
    fun name(value: String) { if (value.isBlank()) invalid(AcademicInputField.NAME) }
    fun dates(start: LocalDate, end: LocalDate) { if (start >= end) invalid(AcademicInputField.DATE_RANGE) }
    when (input) {
        is AcademicYearInput -> { name(input.name); dates(input.startDate, input.endDateExclusive) }
        is SemesterInput -> {
            name(input.name); dates(input.startDate, input.endDateExclusive)
            val weeks = input.academicWeeks
            if (weeks.isEmpty() || weeks.withIndex().any { (i, week) ->
                    week.number.value != i + 1 || week.startDate < input.startDate || week.endDateExclusive > input.endDateExclusive ||
                        (i > 0 && weeks[i - 1].endDateExclusive > week.startDate)
                }) invalid(AcademicInputField.ACADEMIC_WEEKS)
        }
        is PeriodTemplateInput -> {
            name(input.name)
            if (input.periods.isEmpty() || input.periods.zipWithNext().any { (a, b) ->
                    a.number.value >= b.number.value || a.endExclusive > b.start
                }) invalid(AcademicInputField.PERIODS)
        }
        is CourseInput -> { name(input.name); if (input.code?.isBlank() == true) invalid(AcademicInputField.COURSE_CODE) }
        is CourseScheduleRuleInput -> if (input.room?.isBlank() == true) invalid(AcademicInputField.ROOM)
        is ExamInput -> name(input.title)
        else -> error("Unsupported academic authoring input.")
    }
}
