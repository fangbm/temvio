package dev.agenticscheduler.application.academic

import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcademicAuthoringInputTest {
    private val start = LocalDate(2026, 9, 7)
    private val end = LocalDate(2026, 9, 21)
    private val year = AcademicYearId("01900000-0000-7000-8000-000000000001")
    private val term = SemesterId("01900000-0000-7000-8000-000000000002")

    @Test fun `blank names codes and rooms return typed issues without trimming`() {
        assertEquals(listOf(AcademicEditingIssue.InvalidField(AcademicInputField.NAME), AcademicEditingIssue.InvalidField(AcademicInputField.COURSE_CODE)), academicFields(CourseInput(term, " ", "")))
        assertTrue(academicFields(CourseInput(term, " Course ", null)).isEmpty())
        assertEquals(listOf(AcademicEditingIssue.InvalidField(AcademicInputField.ROOM)), academicFields(CourseScheduleRuleInput(CourseId(year.value), DayOfWeek.MONDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)), " ")))
    }

    @Test fun `year range and semester weeks are explicit and never synthesized`() {
        assertEquals(listOf(AcademicEditingIssue.InvalidField(AcademicInputField.DATE_RANGE)), academicFields(AcademicYearInput("Year", end, start)))
        assertEquals(listOf(AcademicEditingIssue.InvalidField(AcademicInputField.ACADEMIC_WEEKS)), academicFields(SemesterInput(year, "Term", start, end, TimeZone.UTC, emptyList<AcademicWeek>().toImmutableList())))
    }

    @Test fun `unordered overlapping and outside weeks are rejected rather than normalized`() {
        val first = AcademicWeek(AcademicWeekNumber(1), start, LocalDate(2026, 9, 14))
        val second = AcademicWeek(AcademicWeekNumber(2), LocalDate(2026, 9, 14), end)
        for (weeks in listOf(listOf(second, first), listOf(first, second.copy(startDate = start, endDateExclusive = LocalDate(2026, 9, 14))), listOf(first, second.copy(startDate = end, endDateExclusive = LocalDate(2026, 9, 28))))) {
            assertEquals(listOf(AcademicEditingIssue.InvalidField(AcademicInputField.ACADEMIC_WEEKS)), academicFields(SemesterInput(year, "Term", start, end, TimeZone.UTC, weeks.toImmutableList())))
        }
    }

    @Test fun `gaps and non-Monday academic boundaries remain valid`() {
        val weeks = listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 8), LocalDate(2026, 9, 15)), AcademicWeek(AcademicWeekNumber(2), LocalDate(2026, 9, 22), LocalDate(2026, 9, 29)))
        assertTrue(academicFields(SemesterInput(year, "Term", start, LocalDate(2026, 10, 1), TimeZone.UTC, weeks.toImmutableList())).isEmpty())
    }

    @Test fun `period templates preserve explicit gaps and reject malformed order`() {
        val first = AcademicPeriod(AcademicPeriodNumber(3), LocalTime(8, 0), LocalTime(9, 0))
        val next = AcademicPeriod(AcademicPeriodNumber(5), LocalTime(9, 30), LocalTime(10, 0))
        assertTrue(academicFields(PeriodTemplateInput("Periods", listOf(first, next).toImmutableList())).isEmpty())
        for (periods in listOf(emptyList(), listOf(next, first), listOf(first, first))) {
            assertEquals(listOf(AcademicEditingIssue.InvalidField(AcademicInputField.PERIODS)), academicFields(PeriodTemplateInput("Periods", periods.toImmutableList())))
        }
    }

    @Test fun `exam schedule variants remain Domain values in the authoring contract`() {
        assertTrue(academicFields(ExamInput(term, null, "Exam", ExamSchedule.Unscheduled)).isEmpty())
        val date = ExamSchedule.DateOnly(start)
        assertEquals(date, ExamInput(term, null, "Exam", date).schedule)
        assertEquals(listOf(AcademicEditingIssue.InvalidField(AcademicInputField.NAME)), academicFields(ExamInput(term, null, " ", date)))
    }
}
