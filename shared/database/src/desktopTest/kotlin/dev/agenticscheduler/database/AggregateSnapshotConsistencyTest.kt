package dev.agenticscheduler.database

import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/** Real WAL Room reads, with a driver barrier after the parent SELECT has established its snapshot.
 * No sleeps, debounce, retry or mapper suppression. The concurrent writer commits before children read.
 */
class AggregateSnapshotConsistencyTest {
    @Test fun semesterObservation() = runBlocking { fixture { f -> observeReplacement(f, "semesters", f.oldTerm, f.newTerm, f.academic.observeSemesters(), f.academic::upsertSemester) } }
    @Test fun periodTemplateObservation() = runBlocking { fixture { f -> observeReplacement(f, "period_templates", f.oldTemplate, f.newTemplate, f.academic.observePeriodTemplates(), f.academic::upsertPeriodTemplate) } }
    @Test fun courseRuleObservation() = runBlocking { fixture { f -> observeReplacement(f, "course_schedule_rules", f.oldRule, f.newRule, f.academic.observeCourseScheduleRules(), f.academic::upsertCourseScheduleRule) } }
    @Test fun planningProfileObservation() = runBlocking { fixture { f -> observeReplacement(f, "planning_profiles", f.oldProfile, f.newProfile, f.profiles.observeAll(), f.profiles::upsert) } }

    @Test fun semesterPointRead() = runBlocking { fixture { f -> pointReplacement(f, "semesters", f.oldTerm, f.newTerm, { f.academic.getSemester(f.oldTerm.id) }, f.academic::upsertSemester) } }
    @Test fun periodTemplatePointRead() = runBlocking { fixture { f -> pointReplacement(f, "period_templates", f.oldTemplate, f.newTemplate, { f.academic.getPeriodTemplate(f.oldTemplate.id) }, f.academic::upsertPeriodTemplate) } }
    @Test fun courseRulePointRead() = runBlocking { fixture { f -> pointReplacement(f, "course_schedule_rules", f.oldRule, f.newRule, { f.academic.getCourseScheduleRule(f.oldRule.id) }, f.academic::upsertCourseScheduleRule) } }
    @Test fun planningProfilePointRead() = runBlocking { fixture { f -> pointReplacement(f, "planning_profiles", f.oldProfile, f.newProfile, { f.profiles.get(f.oldProfile.id) }, f.profiles::upsert) } }

    @Test fun aggregateReadsParticipateInEnclosingWriteTransaction() = runBlocking { fixture { f ->
        assertFailsWith<IllegalStateException> {
            f.db.withWriteTransaction {
                f.academic.upsertSemester(f.newTerm)
                f.academic.upsertPeriodTemplate(f.newTemplate)
                f.academic.upsertCourseScheduleRule(f.newRule)
                f.profiles.upsert(f.newProfile)
                assertEquals(f.newTerm, f.academic.getSemester(f.oldTerm.id))
                assertEquals(f.newTemplate, f.academic.observePeriodTemplates().first().single())
                assertEquals(f.newRule, f.academic.getCourseScheduleRule(f.oldRule.id))
                assertEquals(f.newProfile, f.profiles.observeAll().first().single())
                error("rollback")
            }
        }
        assertEquals(f.oldTerm, f.academic.getSemester(f.oldTerm.id))
        assertEquals(f.oldTemplate, f.academic.getPeriodTemplate(f.oldTemplate.id))
        assertEquals(f.oldRule, f.academic.getCourseScheduleRule(f.oldRule.id))
        assertEquals(f.oldProfile, f.profiles.get(f.oldProfile.id))
    } }

    @Test fun persistedSemesterCorruptionStillFails() = runBlocking { fixture { f ->
        f.db.semesterDao().deleteWeeks(f.oldTerm.id.value)
        assertFailsWith<IllegalArgumentException> { f.academic.observeSemesters().first() }
        assertFailsWith<IllegalArgumentException> { f.academic.getSemester(f.oldTerm.id) }
    } }
    @Test fun persistedPeriodCorruptionStillFails() = runBlocking { fixture { f ->
        f.db.periodTemplateDao().deletePeriods(f.oldTemplate.id.value)
        assertFailsWith<IllegalArgumentException> { f.academic.observePeriodTemplates().first() }
        assertFailsWith<IllegalArgumentException> { f.academic.getPeriodTemplate(f.oldTemplate.id) }
    } }
    @Test fun persistedRuleCorruptionStillFails() = runBlocking { fixture { f ->
        f.db.courseScheduleRuleDao().deleteWeeks(f.oldRule.id.value)
        assertFailsWith<IllegalArgumentException> { f.academic.observeCourseScheduleRules().first() }
        assertFailsWith<IllegalArgumentException> { f.academic.getCourseScheduleRule(f.oldRule.id) }
    } }
    @Test fun persistedProfileCorruptionStillFails() = runBlocking { fixture { f ->
        val parent = f.db.planningProfileDao().get(f.oldProfile.id.value)!!
        f.db.planningProfileDao().upsert(parent.copy(name = ""))
        assertFailsWith<IllegalArgumentException> { f.profiles.observeAll().first() }
        assertFailsWith<IllegalArgumentException> { f.profiles.get(f.oldProfile.id) }
    } }

    private suspend fun <T> observeReplacement(f: Fixture, table: String, old: T, new: T, flow: Flow<List<T>>, write: suspend (T) -> Unit) = coroutineScope {
        val emissions = Channel<T>(Channel.UNLIMITED)
        val observed = mutableListOf<T>()
        val collector = launch { flow.collect { emissions.send(it.single()) } }
        try {
            withTimeout(15_000) {
                observed += emissions.receive().also { assertEquals(old, it) }
                val barrier = f.driver.arm("SELECT * FROM $table ORDER BY id ASC")
                try {
                    write(old) // committed invalidation starts a fresh aggregate read of the old state
                    barrier.reached.await()
                    write(new) // commits while the reader is between parent and children
                } finally { barrier.release.countDown() }
                do { val value = emissions.receive(); observed += value } while (value != new)
                // Drain every already-produced emission as well; none may contain a hybrid.
                while (true) observed += emissions.tryReceive().getOrNull() ?: break
                assertTrue(observed.all { it == old || it == new }, "Only complete committed aggregates may be emitted: $observed")
                assertEquals(new, observed.last())
            }
        } finally { collector.cancelAndJoin() }
    }

    private suspend fun <T> pointReplacement(f: Fixture, table: String, old: T, new: T, read: suspend () -> T?, write: suspend (T) -> Unit) = coroutineScope {
        val barrier = f.driver.arm("SELECT * FROM $table WHERE id = ?")
        val reading = async { read() }
        try {
            withTimeout(15_000) { barrier.reached.await(); write(new) }
        } finally { barrier.release.countDown() }
        assertEquals(old, withTimeout(15_000) { reading.await() }, "The parent-established snapshot must also own the children.")
        assertEquals(new, read())
    }

    private class Barrier(val sql: String) {
        val reached = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
    }
    private class BarrierDriver : SQLiteDriver {
        private val delegate = BundledSQLiteDriver()
        private val armed = AtomicReference<Barrier?>()
        fun arm(sql: String) = Barrier(sql).also { check(armed.compareAndSet(null, it)) }
        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            val hasRow = statement.step()
                            val barrier = armed.get()
                            if (barrier != null && sql.trim() == barrier.sql && armed.compareAndSet(barrier, null)) {
                                barrier.reached.complete(Unit)
                                check(barrier.release.await(15, TimeUnit.SECONDS)) { "Reader barrier timed out: $sql" }
                            }
                            return hasRow
                        }
                    }
                }
            }
        }
    }
    private class Fixture(val db: AgenticSchedulerDatabase, val driver: BarrierDriver) {
        val academic = RoomAcademicRepository(db)
        val profiles = RoomPlanningProfileRepository(db)
        private val start = LocalDate(2026, 9, 7)
        private val middle = LocalDate(2026, 9, 14)
        private val end = LocalDate(2026, 9, 21)
        val year = AcademicYear(AcademicYearId(id(1)), "Year", LocalDate(2026, 1, 1), LocalDate(2027, 1, 1))
        val oldTerm = Semester(SemesterId(id(2)), year.id, "Old term", start, end, TimeZone.UTC, listOf(
            AcademicWeek(AcademicWeekNumber(1), start, middle), AcademicWeek(AcademicWeekNumber(2), middle, end),
        ).toImmutableList())
        val newTerm = oldTerm.copy(name = "New term", endDateExclusive = middle, academicWeeks = listOf(AcademicWeek(AcademicWeekNumber(1), start, middle)).toImmutableList())
        val course = Course(CourseId(id(3)), oldTerm.id, "Course", null)
        val oldTemplate = PeriodTemplate(PeriodTemplateId(id(4)), "Old periods", listOf(
            AcademicPeriod(AcademicPeriodNumber(1), LocalTime(8, 0), LocalTime(9, 0)),
        ).toImmutableList())
        val newTemplate = oldTemplate.copy(name = "New periods", periods = listOf(
            AcademicPeriod(AcademicPeriodNumber(1), LocalTime(10, 0), LocalTime(11, 0)),
            AcademicPeriod(AcademicPeriodNumber(2), LocalTime(11, 0), LocalTime(12, 0)),
        ).toImmutableList())
        val oldRule = CourseScheduleRule(CourseScheduleRuleId(id(5)), course.id, DayOfWeek.MONDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)), "Old room")
        val newRule = oldRule.copy(room = "New room", teachingWeeks = TeachingWeekSet.of(listOf(AcademicWeekNumber(1), AcademicWeekNumber(2))))
        val oldProfile = PlanningProfile(PlanningProfileId(id(6)), "Old profile", configuration(listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(8, 0), LocalTime(10, 0)))))
        val newProfile = oldProfile.copy(name = "New profile", configuration = configuration(listOf(
            WeeklyAvailabilityWindow(DayOfWeek.TUESDAY, LocalTime(10, 0), LocalTime(12, 0)),
            WeeklyAvailabilityWindow(DayOfWeek.WEDNESDAY, LocalTime(10, 0), LocalTime(12, 0)),
        )))
        suspend fun seed() {
            academic.upsertAcademicYear(year); academic.upsertSemester(oldTerm); academic.upsertCourse(course)
            academic.upsertPeriodTemplate(oldTemplate); academic.upsertCourseScheduleRule(oldRule); profiles.upsert(oldProfile)
        }
        private fun configuration(windows: List<WeeklyAvailabilityWindow>) = PlanningProfileConfiguration.Configured(TimeZone.UTC, windows.toImmutableList(), 15.minutes, 30.minutes, 60.minutes, AllDayEventPolicy.NON_BLOCKING)
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val path = Files.createTempDirectory("aggregate-snapshot").resolve("test.db")
        val driver = BarrierDriver()
        val db = Room.databaseBuilder<AgenticSchedulerDatabase>(path.toString()) { AgenticSchedulerDatabaseConstructor.initialize() }
            .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).addCallback(AgentSchemaCallback).build()
        try { val f = Fixture(db, driver); f.seed(); block(f) } finally {
            db.close()
            Files.list(path.parent).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(path.parent)
        }
    }
    companion object { private fun id(n: Int) = "01900000-0000-7000-8000-${n.toString(16).padStart(12, '0')}" }
}
