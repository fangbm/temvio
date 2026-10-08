package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class PlanningProfileSettingsServiceTest {
    @Test fun `ordinary save journals one User update with exact images`() = runBlocking<Unit> {
        val f = Fixture()
        val before = f.seed()
        val after = before.copy(name = "Edited", configuration = configured())
        val count = f.journal.mutations.size
        val counter = f.journal.state!!.lastCounter
        assertEquals(PlanningProfileSettingsResult.Success(after), f.service.save(after, before))
        assertEquals(after, f.profiles.value)
        assertEquals(count + 1, f.journal.mutations.size)
        val operation = f.journal.mutations.last().operation
        assertEquals(MutationOrigin.User, operation.origin)
        assertEquals(PlanningProfilePut(before.toSemanticImage(), after.toSemanticImage()), operation.orderedMutations.single())
        assertEquals(counter + 1, f.journal.state!!.lastCounter)
    }

    @Test fun `stale ordinary draft preserves B and allocates no journal or causal state`() = runBlocking<Unit> {
        val f = Fixture()
        val a = f.seed()
        val b = a.copy(name = "Concurrent B", configuration = configured())
        assertIs<PlanningProfileSettingsResult.Success>(f.service.save(b, a))
        f.assertNoWrite {
            assertEquals(PlanningProfileSettingsResult.Stale, f.service.save(a.copy(name = "Draft C"), a))
        }
        assertEquals(b, f.profiles.value)
    }

    @Test fun `NotFound never recreates a missing profile or allocates causal state`() = runBlocking<Unit> {
        val f = Fixture()
        val a = f.seed()
        f.profiles.value = null
        f.assertNoWrite {
            assertEquals(PlanningProfileSettingsResult.NotFound, f.service.save(a.copy(name = "Draft"), a))
        }
        assertNull(f.profiles.value)
    }

    @Test fun `matching snapshot still crosses the existing conflict write policy`() = runBlocking<Unit> {
        val f = Fixture()
        val before = f.seed()
        val after = before.copy(name = "Edited")
        val block = SyncConflictWriteBlock("open-conflict", EntityKind.PLANNING_PROFILE, before.id.value, listOf("name"))
        val service = f.service(SyncConflictWritePolicy { proposed ->
            assertTrue(f.transactions.active)
            assertEquals(listOf(PlanningProfilePut(before.toSemanticImage(), after.toSemanticImage())), proposed.toList())
            listOf(block)
        })
        f.assertNoWrite {
            assertEquals(listOf(block), assertIs<PlanningProfileSettingsResult.BlockedBySyncConflict>(service.save(after, before)).blocks)
        }
        assertEquals(before, f.profiles.value)
    }

    @Test fun `snapshot is rechecked after entering the coordinator transaction`() = runBlocking<Unit> {
        val f = Fixture()
        val a = f.seed()
        val b = a.copy(name = "B committed before transaction entry")
        f.transactions.beforeEntry = { f.profiles.value = b }
        f.assertNoWrite {
            assertEquals(PlanningProfileSettingsResult.Stale, f.service.save(a.copy(name = "Draft C"), a))
        }
        assertEquals(b, f.profiles.value)
        assertEquals(1, f.profiles.reads, "No preflight read outside the transaction.")
    }

    @Test fun `Agent save retains authorized Agent origin and stale protection`() = runBlocking<Unit> {
        val f = Fixture(agentAllowed = true)
        val a = f.seed()
        val b = a.copy(name = "Agent B")
        val action = id(90)
        val result = assertIs<PlanningProfileAgentSaveResult.Success>(f.service.saveAgent(b, a, action))
        val operation = f.journal.mutations.last().operation
        assertEquals(result.mutationId.value, operation.mutationId)
        assertEquals(MutationOrigin.Agent(action), operation.origin)
        assertEquals(PlanningProfilePut(a.toSemanticImage(), b.toSemanticImage()), operation.orderedMutations.single())
        f.assertNoWrite { assertEquals(PlanningProfileAgentSaveResult.Stale, f.service.saveAgent(a, a, action)) }
        assertEquals(b, f.profiles.value)
    }

    @Test fun `Agent authorization cannot be bypassed while User save remains available`() = runBlocking<Unit> {
        val f = Fixture(agentAllowed = false)
        val a = f.seed()
        val b = a.copy(name = "User B")
        f.assertNoWrite { assertFailsWith<AgentOriginWriteNotAllowed> { f.service.saveAgent(b, a, id(90)) } }
        assertEquals(a, f.profiles.value)
        assertIs<PlanningProfileSettingsResult.Success>(f.service.save(b, a))
    }

    @Test fun `createUnconfigured is explicit User creation without synthesized configuration`() = runBlocking<Unit> {
        val f = Fixture()
        val created = f.seed()
        assertEquals("Explicit draft", created.name)
        assertEquals(PlanningProfileConfiguration.Unconfigured, created.configuration)
        val operation = f.journal.mutations.single().operation
        assertEquals(MutationOrigin.User, operation.origin)
        assertEquals(PlanningProfilePut(null, created.toSemanticImage()), operation.orderedMutations.single())
        assertEquals(created, f.profiles.value)
    }

    private class Fixture(agentAllowed: Boolean = false) {
        val transactions = CheckedTransactions()
        val profiles = Profiles(transactions)
        val journal = Journal()
        private val ids = object : UuidV7Generator { var next = 1; override fun next() = id(next++) }
        private val coordinator = MutationCoordinator(transactions, journal, ids, MutationWallClock { 1 }, AgentOriginWriteGate { agentAllowed })
        val service = service()
        fun service(policy: SyncConflictWritePolicy = NoActiveSyncSpaceWritePolicy) = PlanningProfileSettingsService(profiles, ids, coordinator, policy)
        suspend fun seed() = assertIs<PlanningProfileSettingsResult.Success>(service.createUnconfigured("Explicit draft")).profile
        suspend fun assertNoWrite(block: suspend () -> Unit) {
            val prior = journal.state
            val mutations = journal.mutations.toList()
            val upserts = profiles.upserts
            block()
            assertEquals(mutations, journal.mutations)
            assertEquals(prior, journal.state, "Replica identity, counter, DVV frontier and HLC remain unchanged.")
            assertEquals(upserts, profiles.upserts)
        }
    }

    private class CheckedTransactions : ApplicationTransactionRunner {
        var active = false
        var beforeEntry: (() -> Unit)? = null
        override suspend fun <T> inWriteTransaction(block: suspend () -> T): T {
            beforeEntry?.invoke()
            beforeEntry = null
            active = true
            return try { block() } finally { active = false }
        }
    }

    private class Profiles(val transactions: CheckedTransactions) : PlanningProfileRepository {
        var value: PlanningProfile? = null
        var reads = 0
        var upserts = 0
        override fun observeAll() = flowOf(listOfNotNull(value).toImmutableList())
        override suspend fun get(id: PlanningProfileId): PlanningProfile? {
            assertTrue(transactions.active, "Save must read inside MutationCoordinator.")
            reads++
            return value?.takeIf { it.id == id }
        }
        override suspend fun upsert(profile: PlanningProfile) {
            assertTrue(transactions.active)
            upserts++
            value = profile
        }
    }

    private class Journal : MutationJournalRepository {
        var state: LocalReplicaCausalState? = null
        val mutations = mutableListOf<CommittedMutation>()
        override suspend fun localReplicaState() = state
        override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) { this.state = state }
        override suspend fun appendCommittedMutation(mutation: CommittedMutation) { mutations += mutation }
        override suspend fun advanceFocusBlockTombstones(operation: SyncOperation, acceptedDeletes: List<FocusBlockDelete>) = Unit
    }

    companion object {
        private fun id(n: Int) = "01900000-0000-7000-8000-${n.toString(16).padStart(12, '0')}"
        private fun configured() = PlanningProfileConfiguration.Configured(
            TimeZone.UTC,
            listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(11, 0))).toImmutableList(),
            15.minutes, 30.minutes, 60.minutes, AllDayEventPolicy.NON_BLOCKING,
        )
    }
}
