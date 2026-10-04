package dev.agenticscheduler.application.sync

/** Only the platform can issue/reconstitute a Provider-purpose capability. Not an import destination string. */
class PreparedProviderSecretSlot internal constructor(val reference: SecretReference, val installIdentity: String)

interface PlatformProviderCredentialStore {
    suspend fun prepareProviderSlot(): PreparedProviderSecretSlot
    /** Restore a journal-owned capability after restart, verifying its platform purpose and ownership. */
    suspend fun restoreProviderSlot(reference: SecretReference, installIdentity: String): PreparedProviderSecretSlot?
    suspend fun importPreparedProviderSecret(slot: PreparedProviderSecretSlot, credential: ByteArray)
    suspend fun readProviderSecret(reference: SecretReference): ByteArray?
    /** Deletes only a Provider slot whose platform ownership matches the durable journal. */
    suspend fun deleteProviderSlot(slot: PreparedProviderSecretSlot)
}

internal fun validateProviderCredentialBytes(value: ByteArray) {
    require(value.size in 1..4096 && value.all { it.toInt() in 0x21..0x7e }) { "INVALID_PROVIDER_CREDENTIAL" }
}
