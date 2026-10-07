package dev.agenticscheduler.desktop

import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.time.*
import dev.agenticscheduler.presentation.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*
import org.junit.Test
import kotlin.time.Instant

class CalendarPresentationTest {
    private val date = LocalDate(2026,10,6)
    private val zone = TimeZone.of("America/New_York")
    private fun ref(n: Int) = CalendarSourceRef.Event(EventId("00000000-0000-7000-8000-${n.toString().padStart(12,'0')}"))
    private fun projection(items: List<CalendarItem>) = CalendarProjectionResult(items.toImmutableList(), emptyList<CalendarConflict>().toImmutableList(), emptyList<CalendarProjectionIssue>().toImmutableList())

    @Test fun compactTimedLabelsPreserveOriginalWallRangesAndType() {
        val zoned = CalendarItem.Zoned(ref(1), "night", ZonedTimeRange(Instant.parse("2026-10-07T03:00:00Z"), Instant.parse("2026-10-07T05:00:00Z"), zone))
        check(calendarCompactTime(zoned,zone) == "Zoned · 23:00 – 01:00")
        val floating = CalendarItem.Floating(ref(2),"wall",FloatingTimeRange(LocalDateTime(2026,10,6,23,30),LocalDateTime(2026,10,7,0,30)))
        check(calendarCompactTime(floating,zone) == "Floating · 23:30 – 00:30")
        check(calendarCompactTime(CalendarItem.DateOnly(ref(3),"date",date),zone) == "DateOnly")
    }
    @Test fun dayIsOneExplicitCalendarDate() {
        val v = calendarViewport(date, CalendarMode.DAY, zone)
        check(v.startDate == date && v.endDateExclusive == date.plus(1,DateTimeUnit.DAY)); check(v.displayTimeZone == zone)
    }
    @Test fun weekUsesMondayAndSevenDatesAcrossYearBoundary() {
        val v = calendarViewport(LocalDate(2027,1,1),CalendarMode.WEEK,zone)
        check(v.startDate == LocalDate(2026,12,28)); check(visibleDates(v).size == 7)
    }
    @Test fun everyMonthIsBoundedAndIncludesOnlyNeededLeadingTrailingDates() {
        for (year in 2024..2030) for (month in 1..12) {
            val v = calendarViewport(LocalDate(year,month,15),CalendarMode.MONTH,zone)
            check(visibleDates(v).size in 28..42); check(visibleDates(v).size % 7 == 0)
            check(v.startDate.dayOfWeek == DayOfWeek.MONDAY); check(v.endDateExclusive.dayOfWeek == DayOfWeek.MONDAY)
            val first = LocalDate(year,month,1); val next = first.plus(1,DateTimeUnit.MONTH)
            check(v.startDate <= first && v.endDateExclusive >= next)
            check(v.startDate.plus(7,DateTimeUnit.DAY) > first && v.endDateExclusive.plus(-7,DateTimeUnit.DAY) < next)
        }
    }
    @Test fun dstDayMembershipUsesRealCalendarBoundaries() {
        val spring = LocalDate(2026,3,8)
        val item = CalendarItem.Zoned(ref(1),"DST",ZonedTimeRange(Instant.parse("2026-03-08T06:30:00Z"),Instant.parse("2026-03-09T04:00:00Z"),zone))
        val cells = calendarCells(projection(listOf(item)),calendarViewport(spring,CalendarMode.WEEK,zone))
        check(cells.last().summaries.single() == item)
        check(!item.intersectsLocalDate(spring.plus(1,DateTimeUnit.DAY),zone))
    }
    @Test fun crossMidnightZonedUsesSameSourceOnBothDates() {
        val item = CalendarItem.Zoned(ref(1),"night",ZonedTimeRange(Instant.parse("2026-10-07T03:30:00Z"),Instant.parse("2026-10-07T05:00:00Z"),zone))
        val cells = calendarCells(projection(listOf(item)),calendarViewport(date,CalendarMode.WEEK,zone))
        check(cells.filter { it.summaries.isNotEmpty() }.map { it.date } == listOf(date,date.plus(1,DateTimeUnit.DAY)))
        check(cells.flatMap { it.summaries }.map { it.source }.distinct() == listOf(item.source))
    }
    @Test fun floatingNightStaysWallTimeAcrossTwoDates() {
        val item = CalendarItem.Floating(ref(2),"night",FloatingTimeRange(LocalDateTime(2026,10,6,23,0),LocalDateTime(2026,10,7,1,0)))
        val cells = calendarCells(projection(listOf(item)),calendarViewport(date,CalendarMode.WEEK,zone))
        check(cells.count { it.summaries.isNotEmpty() } == 2); check(calendarRow(item,zone).detail.contains("no timezone"))
    }
    @Test fun multiDayAllDayPreservesExclusiveDateAndIdentity() {
        val item = CalendarItem.AllDay(ref(3),"dates",AllDayRange(date,date.plus(3,DateTimeUnit.DAY)))
        val cells = calendarCells(projection(listOf(item)),calendarViewport(date,CalendarMode.WEEK,zone))
        check(cells.count { it.summaries.isNotEmpty() } == 3); check(cells.flatMap { it.summaries }.all { it.source == item.source })
    }
    @Test fun dateOnlyHasOneDateAndNoInventedInstant() {
        val item = CalendarItem.DateOnly(ref(4),"date",date)
        check(calendarCells(projection(listOf(item)),calendarViewport(date,CalendarMode.MONTH,zone)).count { it.summaries.isNotEmpty() } == 1)
        check(calendarRow(item,zone).time == "DateOnly · $date"); check(calendarRow(item,zone).detail.contains("no invented time"))
    }
    @Test fun largeBoundedFixtureKeepsThreeSummariesAndExactOverflow() {
        val items = (1..1000).map { CalendarItem.DateOnly(ref(it),"Very long title ".repeat(20),date) }
        val cell = calendarCells(projection(items),calendarViewport(date,CalendarMode.MONTH,zone)).single { it.date == date }
        check(cell.summaries.size == 3); check(cell.overflow == 997); check(cell.summaries == items.take(3))
    }
    @Test fun semanticLabelsAndProjectionConflictsRemainSeparate() {
        val item = CalendarItem.AllDay(ref(1),"AllDay",AllDayRange(date,date.plus(1,DateTimeUnit.DAY)))
        val p = projection(listOf(item)).copy(conflicts = listOf(CalendarConflict(ref(1),ref(2))).toImmutableList())
        check(calendarCells(p,calendarViewport(date,CalendarMode.DAY,zone)).single().summaries == listOf(item))
        check(p.syncConflictRefs.isEmpty()); check(p.conflicts.size == 1); check(calendarRow(item,zone).time.startsWith("AllDay"))
    }
}
