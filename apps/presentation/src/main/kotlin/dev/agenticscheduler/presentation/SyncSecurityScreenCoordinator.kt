package dev.agenticscheduler.presentation

import androidx.compose.runtime.*
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.application.history.SyncConflictQueryService
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.CancellationException

data class EnrollmentDisplay(val accountId: AccountId, val deviceId: DeviceId, val space: SyncSpaceId?, val state: String)
data class ConflictDisplay(val id: String, val kind: SyncConflictKind, val status: SyncConflictStatus,
    val entities: List<SyncConflictEntityRef>, val participants: List<MutationId>, val provisional: MutationId,
    val resolution: MutationId?, val supersededBy: String?)

/** Only non-secret projection metadata enters screen state; candidate JSON is never retained. */
class SyncSecurityScreenCoordinator(
    private val enrollments: LocalEnrollmentRepository,
    private val conflicts: SyncConflictQueryService,
    private val agentState: AgentStateRepository,
) {
    var enrollment by mutableStateOf<List<EnrollmentDisplay>>(emptyList()); private set
    var openConflicts by mutableStateOf<List<ConflictDisplay>>(emptyList()); private set
    var selected by mutableStateOf<ConflictDisplay?>(null); private set
    var agentBusinessEnabled by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    val activeSpace: SyncSpaceId? get() = enrollment.filter { it.state == "ACTIVE" }.singleOrNull()?.space

    suspend fun refresh() = command {
        enrollment = enrollments.states().map { state -> when (state) {
            is LocalEnrollmentState.Active -> EnrollmentDisplay(state.accountId,state.deviceId,state.syncSpaceId,"ACTIVE")
            is LocalEnrollmentState.Pending -> EnrollmentDisplay(state.accountId,state.deviceId,null,"PENDING")
        } }
        val space = activeSpace
        openConflicts = space?.let { conflicts.listOpen(it).map { value -> value.display() } }.orEmpty()
        agentBusinessEnabled = space?.let { agentState.syncAgentOriginEnabled(it) } ?: false
        loaded = true
    }
    suspend fun inspect(id: String) = command {
        selected = conflicts.get(id)?.takeIf { it.syncSpaceId == activeSpace }?.display()
        if (selected == null) message = "Business conflict not found in the current SyncSpace."
    }
    fun closeDetail() { if (!busy) selected = null }
    suspend fun setAgentBusinessFromUser(enabled: Boolean) = command {
        val space = activeSpace ?: return@command
        agentState.setSyncAgentOriginEnabled(space,enabled)
        agentBusinessEnabled = agentState.syncAgentOriginEnabled(space)
        message = "Agent-origin business compatibility choice saved. Conversation consent is unchanged."
    }
    private suspend fun command(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Sync metadata could not be read or saved. Refresh before retrying." }
        finally { busy = false }
    }
}

private fun SyncConflict.display() = ConflictDisplay(conflictId,kind,status,entityRefs,participants.map { it.mutationId },provisionalMutationId,resolutionMutationId,supersededByConflictId)

/** Runtime facts supplied by the platform host, never inferred from outbound rows. */
data class SyncRuntimeDisplay(val configured: Boolean, val stoppedReason: String?)

/** Existing Application security commands. No transport, key bytes or secret-store APIs in screens. */
data class SecurityWorkflowServices(
    val accountId: AccountId,
    val recovery: RecoveryEnrollmentService,
    val revocation: RevocationRotationService,
    val activeDeviceIds: suspend () -> List<DeviceId>,
    val newDeviceId: () -> DeviceId,
    val newRequestId: () -> EnrollmentRequestId,
    val newRotationId: () -> String,
    val existingPendingIdentity: suspend () -> Pair<DeviceId,EnrollmentRequestId>?,
    val afterEnrollment: suspend () -> Unit,
)

/** Prepared rotation is opaque and session-only; an uncertain submit retries that exact attempt. */
class SecurityWorkflowCoordinator(private val services: SecurityWorkflowServices) {
    var devices by mutableStateOf<List<DeviceId>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    private var retry by mutableStateOf<PreparedRevocationRotation?>(null)
    val hasRotationRetry: Boolean get() = retry != null
    private var recoveryIdentity: Pair<DeviceId,EnrollmentRequestId>? = null

    suspend fun refreshDevices() = command { devices = services.activeDeviceIds() }
    suspend fun recoverFromUser(secret: RecoverySecret) = command {
        val identity = recoveryIdentity ?: (services.existingPendingIdentity() ?: (services.newDeviceId() to services.newRequestId())).also { recoveryIdentity = it }
        val result = services.recovery.recover(services.accountId,secret,identity.first,identity.second)
        message = when (result) {
            is RecoveryEnrollmentServiceResult.Activated -> {
                try {
                    services.afterEnrollment()
                    "Recovery enrollment activated. Runtime configuration refreshed."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { "Enrollment activated, but runtime activation is unavailable. Business screens remain guarded; reopen or retry setup." }
            }
            is RecoveryEnrollmentServiceResult.Existing -> "Existing enrollment retained. No new identity substituted."
            else -> "Recovery not completed: ${result::class.simpleName}. Retry retains the same attempt identity; no secret is displayed."
        }
    }
    suspend fun revokeFromUser(target: DeviceId, secret: RecoverySecret, confirmed: Boolean) = command {
        if (!confirmed || retry != null) return@command
        consume(services.revocation.prepare(services.accountId,target,services.newRotationId(),secret))
        retry?.let { consume(services.revocation.submit(it)) }
    }
    suspend fun retryRotationFromUser() = command { retry?.let { consume(services.revocation.submit(it)) } }
    private fun consume(result: RevocationRotationResult) {
        when (result) {
            is RevocationRotationResult.Prepared -> { retry = result.value; message = "Rotation prepared; not yet committed." }
            is RevocationRotationResult.RemoteFailed -> { retry = result.retry; message = "Rotation submission uncertain. Retry the exact prepared attempt." }
            is RevocationRotationResult.LocalCommitFailed -> { retry = result.retry; message = "Rotation local publication failed. Retry the exact prepared attempt." }
            is RevocationRotationResult.Committed -> { retry = null; message = "Revocation and future-key rotation committed. Previously possessed material is not erased." }
            else -> message = "Revocation not completed: ${result::class.simpleName}. No success is claimed."
        }
    }
    private suspend fun command(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Security operation did not complete. No secret-bearing error is displayed." }
        finally { busy = false }
    }
}
