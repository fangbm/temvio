package dev.agenticscheduler.wear.agent

import android.content.Context
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentSyncWriteGate
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.agent.runtime.*
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.id.*
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.*
import dev.agenticscheduler.wear.capability.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

class WearV2BusinessWriteGate(private val enrollments: LocalEnrollmentRepository, private val state: AgentStateRepository) :
    AgentOriginWriteGate, AgentOutboundCompatibilityGate {
    override suspend fun mayCommit(): Boolean {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        return when (active.size) { 0 -> true; 1 -> AgentSyncWriteGate(active.single().accountId, enrollments, state).mayCommit(); else -> false }
    }
    override suspend fun enabled(syncSpaceId: SyncSpaceId): Boolean {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        return active.singleOrNull()?.takeIf { it.syncSpaceId == syncSpaceId }?.let { AgentSyncWriteGate(it.accountId, enrollments, state).enabled(syncSpaceId) } ?: false
    }
}

/** Manual platform composition of the existing runtime, Tool set and deterministic application paths. */
class WearAgentRuntimeComposition private constructor(
    val state: RoomAgentStateRepository,
    val readiness: WearReadinessComposition,
    val session: WearAgentSessionController,
    private val revisions: RoomProviderCredentialProvisioningRepository,
    private val target: DeviceId?,
    private val client: HttpClient,
) {
    @Volatile private var foreground = false
    fun stopForegroundFromLifecycle() {
        foreground = false
        session.cancelForegroundRequest()
        readiness.capability.cancel()
    }
    suspend fun foregroundChanged(value: Boolean) {
        foreground = value
        readiness.foregroundChanged(value)
    }
    suspend fun refreshSelectedBinding() {
        val config = state.selectedProviderConfigId()?.let { state.providerConfig(it) }
        val revision = config?.let { target?.let { device -> revisions.state(device, config.id.value) } }
        val installed = revision?.let { it.activeBinding ?: it.approvedBinding ?: config?.provisioningBinding(config.id, true) }
        val binding = when {
            installed != null -> WearProviderBinding(installed, WearEndpointRoute.PUBLIC_HTTPS)
            config != null && config.credentialReference == null -> WearEndpointRoute.entries.map {
                WearProviderBinding(config.provisioningBinding(config.id, false), it)
            }.firstOrNull(readiness.settings::isCredentialFreeBindingApproved)
            else -> null
        }
        readiness.bindingChanged(binding)
    }
    suspend fun selectFromUserAction(config: ProviderConfig) { state.selectProviderConfig(config.id); refreshSelectedBinding() }
    suspend fun approveCredentialFreeFromUserAction(config: ProviderConfig, route: WearEndpointRoute) {
        require(config.credentialReference == null)
        readiness.settings.approveCredentialFreeBindingFromExplicitUserAction(WearProviderBinding(config.provisioningBinding(config.id, false), route))
        selectFromUserAction(config)
    }
    suspend fun close() { readiness.close(); client.close() }

    companion object {
        suspend fun createActive(context: Context, database: AgenticSchedulerDatabase, scope: CoroutineScope, d8: RoomD8RuntimeComposition,
            requestMicrophone: ((Boolean) -> Unit) -> Unit, languageTag: String,
            ids: UuidV7Generator = productionUuidV7Generator(), clock: AgentClock = AgentClock { Clock.System.now().toEpochMilliseconds() },
        ): WearAgentRuntimeComposition {
            val state = RoomAgentStateRepository(database); val enrollments = RoomLocalEnrollmentRepository(database)
            val target = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull()?.deviceId
            return withContext(Dispatchers.Main.immediate) {
                build(context, database, scope, d8, requestMicrophone, languageTag, ids, clock, state, enrollments,
                    RoomProviderCredentialProvisioningRepository(database), WearReadinessComposition.newProviderHttpClient(), target)
            }
        }
        private fun build(context: Context, database: AgenticSchedulerDatabase, scope: CoroutineScope, d8: RoomD8RuntimeComposition,
            requestMicrophone: ((Boolean) -> Unit) -> Unit, languageTag: String, ids: UuidV7Generator, clock: AgentClock,
            local: RoomAgentStateRepository, enrollments: RoomLocalEnrollmentRepository, revisions: RoomProviderCredentialProvisioningRepository,
            client: HttpClient, target: DeviceId?,
        ): WearAgentRuntimeComposition {
            val readiness = WearReadinessComposition.create(context, database, target, languageTag, scope, client, requestMicrophone)
            lateinit var composition: WearAgentRuntimeComposition
            val secrets = AndroidKeystoreSecureStore(context)
            val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { ref ->
                val expected = coroutineContext[WearCommandLease]?.snapshot ?: return@ProviderCredentialResolver null
                if (!composition.foreground || expected.config.credentialReference != ref || !readiness.authorizes(expected, expected.config)) return@ProviderCredentialResolver null
                val bytes = secrets.readProviderSecret(ref) ?: return@ProviderCredentialResolver null
                try { if (readiness.authorizes(expected, expected.config)) bytes.decodeToString() else null } finally { bytes.fill(0) }
            }, ProviderRequestGuard { config ->
                val expected = coroutineContext[WearCommandLease]?.snapshot
                when {
                    !composition.foreground -> "FOREGROUND_REQUIRED"
                    expected == null -> readiness.readiness.value.runtimeState.name.takeUnless { it == "READY" } ?: "READINESS_CHANGED"
                    local.selectedProviderConfigId() != expected.config.id -> "BINDING_CHANGED"
                    !readiness.authorizes(expected, config) -> readiness.readiness.value.runtimeState.name.takeUnless { it == "READY" } ?: "READINESS_CHANGED"
                    else -> null
                }
            })
            val state = WatchAgentState(local) {
                val lease = coroutineContext[WearCommandLease]
                lease?.newCommand != true || composition.foreground && lease.snapshot?.let { readiness.authorizes(it, it.config) } == true
            }
            val journal = RoomMutationJournalRepository(database)
            val mutations = MutationCoordinator(RoomApplicationTransactionRunner(database), journal, ids,
                MutationWallClock { clock.nowEpochMillis() }, WearV2BusinessWriteGate(enrollments, local))
            val tasks = RoomTaskRepository(database); val events = RoomEventRepository(database); val profiles = RoomPlanningProfileRepository(database)
            val academics = RoomAcademicRepository(database)
            val taskEditor = TaskEditingService(tasks, ids, mutations, d8.writePolicy)
            val eventEditor = EventEditingService(events, ids, mutations, d8.writePolicy)
            val history = HistoryQueryService(journal)
            val planner = DogfoodPlannerService(tasks, events, profiles, academics, ids, mutations = mutations,
                conflictWritePolicy = d8.writePolicy, sourceFacts = d8.sourceFacts)
            val runtime = AgentRunService(state, provider, TaskGetTool(tasks), TaskCreateTool(taskEditor), ids, clock,
                taskList = TaskListTool(tasks), historyReads = HistoryReadTools(history), taskUpdate = TaskUpdateTool(taskEditor),
                calendarList = CalendarListTool(ConflictAwareSourceFactReadService(events, tasks, profiles, academics, d8.sourceFacts)),
                eventCreate = EventCreateTool(eventEditor), eventUpdate = EventUpdateTool(eventEditor),
                plannerFullReplan = PlannerPreviewFullReplanTool(DogfoodPlannerPreviewApplication(planner)),
                plannerLocalReflow = PlannerPreviewLocalReflowTool(DogfoodPlannerPreviewApplication(planner)),
                historyUndo = HistoryUndoTool(D7HistoryUndoApplication(UndoService(mutations, journal, events, tasks, profiles, d8.writePolicy), history)),
                planningProfileUpdate = PlanningProfileUpdateTool(PlanningProfileSettingsService(profiles, ids, mutations, d8.writePolicy)),
                plannerApplyBranch = PlannerApplyBranchTool(planner), capabilityProbe = { config ->
                    val expected = coroutineContext[WearCommandLease]?.snapshot
                    if (expected == null || expected.config != config) ProviderProbeResult.Unavailable("READINESS_CHANGED")
                    else readiness.structuredCapability(expected)
                })
            val session = WearAgentSessionController(state, runtime, { if (composition.foreground) readiness.requestSnapshot() else null }, { readiness.readiness.value.runtimeState }, continuationBlock = { thread ->
                // Only block on existing conflict/tombstone facts; no remote content enters Provider context.
                val sync = RoomAgentSyncPersistence(database)
                enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().firstNotNullOfOrNull { active ->
                    val projection = sync.threadHistoryProjection(active.syncSpaceId, AgentThreadSyncId(thread.value))
                    if (projection.tombstoned) "THREAD_TOMBSTONED" else if (!projection.providerContinuationAllowed) "AGENT_HISTORY_CONFLICT" else null
                }
            }, foreground = { composition.foreground })
            composition = WearAgentRuntimeComposition(local, readiness, session, revisions, target, client)
            return composition
        }
    }
}
