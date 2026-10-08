package dev.agenticscheduler.database

import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class PlanningProfileOptimisticSaveIntegrationTest {
    @Test fun `real Room User save rejects stale editor after committed aggregate update`() = runBlocking<Unit> {
        val db = openInMemoryDesktopDatabase()
        try {
            val profiles = RoomPlanningProfileRepository(db)
            val journal = RoomMutationJournalRepository(db)
            val ids = object : UuidV7Generator {
                var next = 1
                override fun next() = "01900000-0000-7000-8000-${(next++).toString(16).padStart(12, '0')}"
            }
            val service = PlanningProfileSettingsService(profiles, ids,
                MutationCoordinator(RoomApplicationTransactionRunner(db), journal, ids, MutationWallClock { 1 }),
                NoActiveSyncSpaceWritePolicy)
            val a = assertIs<PlanningProfileSettingsResult.Success>(service.createUnconfigured("A")).profile
            val expected = profiles.get(a.id)!!
            val b = a.copy(name = "B", configuration = PlanningProfileConfiguration.Configured(
                TimeZone.UTC,
                listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(12, 0))).toImmutableList(),
                15.minutes, 30.minutes, 60.minutes, AllDayEventPolicy.NON_BLOCKING,
            ))
            assertEquals(PlanningProfileSettingsResult.Success(b), service.save(b, a))
            val committed = journal.timeline()
            val state = journal.localReplicaState()
            assertEquals(2, committed.size)
            assertEquals(MutationOrigin.User, committed.last().operation.origin)
            val put = assertIs<PlanningProfilePut>(committed.last().operation.orderedMutations.single())
            assertEquals(a.id.value, put.before!!.id)
            assertEquals("A", put.before!!.name)
            assertEquals(PlanningProfileConfigurationImage.Unconfigured, put.before!!.configuration)
            assertEquals(b.id.value, put.after.id)
            assertEquals("B", put.after.name)
            val configuration = assertIs<PlanningProfileConfigurationImage.Configured>(put.after.configuration)
            assertEquals("UTC", configuration.timeZone)
            assertEquals(1, configuration.weeklyAvailability.size)

            assertEquals(PlanningProfileSettingsResult.Stale, service.save(expected.copy(name = "C"), expected))
            assertEquals(b, profiles.get(a.id), "Parent and availability children remain the committed B aggregate.")
            assertEquals(committed, journal.timeline(), "No C write enters the real Room D7 journal.")
            assertEquals(state, journal.localReplicaState(), "No counter, frontier or HLC advance on Stale.")
        } finally {
            db.close()
        }
    }
}
