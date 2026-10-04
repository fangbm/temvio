package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.*
import kotlinx.serialization.Serializable

@Serializable enum class ProviderReservationPhase { NONE, RESERVED, AUTHENTICATED, USER_APPROVED, ACTIVE, DISABLED }
@Serializable enum class ProviderInstallPhase { PREPARED_IMPORT, SECRET_IMPORTED, METADATA_COMMITTED, CLEANUP_PENDING, COMPLETED, REJECTED }

/** Non-secret metadata only. Accepted counters are historical; a wipe barrier is not an install. */
@Serializable
data class ProviderCredentialRevisionState(
    val targetDeviceId: DeviceId,
    val providerConfigId: String,
    val highestReservedRevision: Long = 0,
    val highestAcceptedRevision: Long = 0,
    val rejectionFloor: Long = 0,
    val liveRevision: Long? = null,
    val selectedProvisioner: DeviceId? = null,
    val phase: ProviderReservationPhase = ProviderReservationPhase.NONE,
    val approvedBinding: WearProviderBindingMetadataV1? = null,
    val envelopeDigest: String? = null,
    val approvalBindingDigest: String? = null,
    val activeReference: String? = null,
    val activeInstallIdentity: String? = null,
    val activeBinding: WearProviderBindingMetadataV1? = null,
    val acceptedEnvelopeDigest: String? = null,
    val acceptedProvisioner: DeviceId? = null,
    val generation: Long = 0,
) {
    init {
        MutationId(providerConfigId)
        require(highestReservedRevision >= 0 && highestAcceptedRevision >= 0 && rejectionFloor >= 0 && generation >= 0)
        require(highestAcceptedRevision <= highestReservedRevision)
        require((activeReference == null) == (activeInstallIdentity == null))
        require(liveRevision == null || liveRevision in 1..highestReservedRevision)
        require(approvedBinding == null || approvedBinding.providerConfigId == providerConfigId)
    }
    fun nextRevision(): Long {
        val maximum = maxOf(highestReservedRevision, highestAcceptedRevision, rejectionFloor)
        if (maximum == Long.MAX_VALUE) throw ProviderProvisioningException(ProviderCredentialRejection.REVISION_OVERFLOW)
        return maximum + 1
    }
}

@Serializable
data class ProviderCredentialInstallJournal(
    val targetDeviceId: DeviceId,
    val providerConfigId: String,
    val revision: Long,
    val preparedReference: String,
    val installIdentity: String,
    val priorActiveReference: String?,
    val priorInstallIdentity: String?,
    val phase: ProviderInstallPhase,
)

@Serializable enum class ProviderDeliveryOutboxState { PREPARED, UPLOADED, ACKNOWLEDGED, DELIVERY_EXPIRED }
@Serializable
data class ProviderCredentialDeliveryOutbox(
    val sourceDeviceId: DeviceId,
    val envelope: ProviderCredentialEnvelopeV1,
    val canonicalEnvelopeJson: String,
    val envelopeDigest: String,
    val state: ProviderDeliveryOutboxState,
    val expiresAtEpochSeconds: Long,
)

class ProviderProvisioningException(val reason: ProviderCredentialRejection) : IllegalStateException(reason.name)

/** Implemented with the existing Room write transaction, including ProviderConfig credential association. */
interface ProviderCredentialProvisioningRepository {
    /** Only known-new configs under an initialized enrollment may start at zero. Missing known state fails closed. */
    suspend fun openConfig(target: DeviceId, config: String): ProviderCredentialRevisionState
    suspend fun state(target: DeviceId, config: String): ProviderCredentialRevisionState?
    suspend fun compareAndSet(expected: ProviderCredentialRevisionState, updated: ProviderCredentialRevisionState,
        journals: List<ProviderCredentialInstallJournal> = emptyList()): Boolean
    suspend fun journals(target: DeviceId): List<ProviderCredentialInstallJournal>
    suspend fun updateJournal(expected: ProviderCredentialInstallJournal, updated: ProviderCredentialInstallJournal): Boolean
    suspend fun saveDelivery(value: ProviderCredentialDeliveryOutbox): ProviderCredentialDeliveryOutbox
    suspend fun markDeliveryUploaded(expected: ProviderCredentialDeliveryOutbox): Boolean
    suspend fun delivery(source: DeviceId, target: DeviceId, config: String, revision: Long): ProviderCredentialDeliveryOutbox?
    /** End retry retention for this exact delivery only; never affects target installation/counters. */
    suspend fun removeDelivery(source: DeviceId, target: DeviceId, config: String, revision: Long)
}
