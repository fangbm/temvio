package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.*
import kotlinx.serialization.Serializable

@Serializable enum class ProviderReservationPhase { NONE, RESERVED, AUTHENTICATED, USER_APPROVED, ACTIVE, DISABLED }
@Serializable enum class ProviderInstallPhase { PREPARED_IMPORT, SECRET_IMPORTED, METADATA_COMMITTED, CLEANUP_PENDING, COMPLETED, REJECTED }

/** Local metadata arithmetic only, never a wire revision. A wipe barrier may exceed the wire's Long range. */
@Serializable
@JvmInline
value class ProviderLocalCounter(val decimal: String) : Comparable<ProviderLocalCounter> {
    init { require(Regex("0|[1-9][0-9]*").matches(decimal)) }
    override fun compareTo(other: ProviderLocalCounter): Int =
        decimal.length.compareTo(other.decimal.length).takeIf { it != 0 } ?: decimal.compareTo(other.decimal)
    operator fun compareTo(other: Long): Int = compareTo(of(other))
    operator fun plus(step: Int): ProviderLocalCounter {
        require(step == 1)
        val digits = decimal.toCharArray()
        for (i in digits.lastIndex downTo 0) {
            if (digits[i] != '9') { digits[i]++; return ProviderLocalCounter(digits.concatToString()) }
            digits[i] = '0'
        }
        return ProviderLocalCounter("1" + digits.concatToString())
    }
    fun toLongExact(): Long = decimal.toLong()
    companion object {
        val ZERO = ProviderLocalCounter("0")
        fun of(value: Long): ProviderLocalCounter { require(value >= 0); return ProviderLocalCounter(value.toString()) }
    }
}
operator fun Long.compareTo(other: ProviderLocalCounter): Int = ProviderLocalCounter.of(this).compareTo(other)

/** Non-secret metadata only. Accepted counters are historical; a wipe barrier is not an install. */
@Serializable
data class ProviderCredentialRevisionState(
    val targetDeviceId: DeviceId,
    val providerConfigId: String,
    val highestReservedRevision: Long = 0,
    val highestAcceptedRevision: Long = 0,
    val rejectionFloor: ProviderLocalCounter = ProviderLocalCounter.ZERO,
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
    val generation: ProviderLocalCounter = ProviderLocalCounter.ZERO,
) {
    init {
        MutationId(providerConfigId)
        require(highestReservedRevision >= 0 && highestAcceptedRevision >= 0)
        require(highestAcceptedRevision <= highestReservedRevision)
        require((activeReference == null) == (activeInstallIdentity == null))
        require(liveRevision == null || liveRevision in 1..highestReservedRevision)
        require(approvedBinding == null || approvedBinding.providerConfigId == providerConfigId)
    }
    fun nextRevision(): Long {
        val maximum = maximumCounter()
        if (maximum >= Long.MAX_VALUE) throw ProviderProvisioningException(ProviderCredentialRejection.REVISION_OVERFLOW)
        return (maximum + 1).toLongExact()
    }
    fun nextRejectionFloor(): ProviderLocalCounter = maximumCounter() + 1
    private fun maximumCounter() = maxOf(ProviderLocalCounter.of(highestReservedRevision), ProviderLocalCounter.of(highestAcceptedRevision), rejectionFloor)
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
