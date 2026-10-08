package dev.agenticscheduler.fixtures

import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

/** D10-03 synthetic facts only. Fixture seeding is not a product profile authoring API. */
class D10ProductFixtureGraph(val db: AgenticSchedulerDatabase) {
    val base = D10FixtureGraph(db)
    val now = Instant.parse("2026-10-06T08:00:00Z")
    val space = SyncSpaceId("d10-product-space")
    val receive = RoomSyncReceiveRepository(db)
    val policy = SyncConflictWriteGuard(receive,space)
    val source = ActiveConflictAwareSourceFactQuery(ConflictAwareProjection(receive),space)
    val reads = ConflictAwareSourceFactReadService(base.events,base.tasks,base.profiles,base.academics,source)
    val planner = DogfoodPlannerService(base.tasks,base.events,base.profiles,base.academics,base.ids,
        mutations=base.mutations,conflictWritePolicy=policy,sourceFacts=source)
    val profileSettings = PlanningProfileSettingsService(base.profiles,base.ids,base.mutations,policy)
    val queries = HistoryQueryService(base.journal)
    val undo = UndoService(base.mutations,base.journal,base.events,base.tasks,base.profiles,policy)
    val conflicts = SyncConflictQueryService(receive)
    lateinit var configured: PlanningProfile
    lateinit var unconfigured: PlanningProfile
    suspend fun seed() {
        base.seed()
        unconfigured = (profileSettings.createUnconfigured("Explicit draft profile") as PlanningProfileSettingsResult.Success).profile
        val opening = (profileSettings.createUnconfigured("Research hours") as PlanningProfileSettingsResult.Success).profile
        configured = opening.copy(configuration=PlanningProfileConfiguration.Configured(
            base.zone,listOf(WeeklyAvailabilityWindow(DayOfWeek.TUESDAY,LocalTime(9,0),LocalTime(17,0))).toImmutableList(),
            25.minutes,50.minutes,90.minutes,AllDayEventPolicy.NON_BLOCKING))
        check(profileSettings.save(configured, opening) is PlanningProfileSettingsResult.Success)
    }
    fun workspace() = PlannerWorkspaceCoordinator(reads,planner,profileSettings)
    fun history() = HistoryScreenCoordinator(queries,undo)
    fun sync() = SyncSecurityScreenCoordinator(base.enrollments,conflicts,base.agent)
    fun request(mode: PlannerMode=PlannerMode.FULL_REPLAN) = PlannerRequestDraft(mode,configured.id,now.toString(),
        now.toString(),"2026-10-06T17:00:00Z",searchStart=now.toString(),searchEnd="2026-10-06T17:00:00Z")
    suspend fun enroll() {
        base.enrollments.saveActive(LocalEnrollmentState.Active(AccountId("d10-account"),DeviceId("d10-device"),EnrollmentRequestId("d10-request"),
            HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),SecretReference("fixture://private"),space,
            SecretReference("fixture://amk"),SecretReference("fixture://credential")))
    }
    suspend fun profileConflict(): SyncConflict {
        // Room metadata fixtures use canonical typed operations. Product presentation never decodes them.
        val image=(base.journal.timeline().flatMap {it.operation.orderedMutations}.filterIsInstance<PlanningProfilePut>().last {it.after.id==configured.id.value}).after
        val participants = listOf("00000000-0000-7000-8000-000000009001","00000000-0000-7000-8000-000000009002").map { replica ->
            val id=base.ids.next()
            val value=image.copy(name="${replica} research")
            val op=SyncOperation(id,DvvSnapshot(emptyList(),DotSnapshot(replica,1)),HlcSnapshot(1,0,replica),MutationOrigin.User,
                listOf(PlanningProfilePut(image,value)))
            SyncConflictParticipant(MutationId(id),op.dvv,LocalJournalCodec.encode(op))
        }.sortedBy {it.mutationId.value}
        val conflict=SyncConflict("d10-profile-conflict",space,listOf(SyncConflictEntityRef(EntityKind.PLANNING_PROFILE,configured.id.value,listOf("name"))),
            participants,participants.first().mutationId,SyncConflictKind.SEMANTIC,commonCausalContextOf(participants),SyncConflictStatus.OPEN)
        RoomApplicationTransactionRunner(db).inWriteTransaction {receive.saveConflict(conflict)}
        return conflict
    }
}
