package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.*

/** Fixed C1 contextInfo. Uses the existing enrolled D8 HPKE identity and primitive. */
object ProviderCredentialContextV1 {
    fun bytes(target: DeviceId, config: String, revision: Long): ByteArray {
        MutationId(config); require(revision > 0)
        return "agentic-scheduler-provider-credential".encodeToByteArray() + byteArrayOf(0) + credentialU32(1u) +
            credentialLP(target.value) + credentialLP(config) + credentialU64(revision.toULong())
    }
}

object ProviderProvisioningSas {
    fun transcript(source: DeviceId, envelope: ProviderCredentialEnvelopeV1, binding: WearProviderBindingMetadataV1): ByteArray {
        require(envelope.providerConfigId == binding.providerConfigId)
        return "agentic-scheduler-provider-provisioning-sas".encodeToByteArray() + byteArrayOf(0) +
            credentialLP(source.value) + credentialLP(envelope.targetDeviceId.value) + credentialLP(envelope.providerConfigId) +
            credentialU64(envelope.credentialRevision.toULong()) + pairingSha256(ProviderCredentialWireCodec.encodeEnvelope(envelope)) +
            pairingSha256(ProviderCredentialWireCodec.encodeBinding(binding))
    }
    fun calculate(source: DeviceId, envelope: ProviderCredentialEnvelopeV1, binding: WearProviderBindingMetadataV1): String {
        val input = transcript(source, envelope, binding)
        var counter = 0u
        while (true) {
            val digest = pairingSha256(input + credentialU32(counter))
            val sample = (0 until 8).fold(0uL) { result, i -> (result shl 8) or digest[i].toUByte().toULong() }
            if (sample < 18_446_744_073_700_000_000uL) return (sample % 100_000_000uL).toString().padStart(8, '0')
            check(counter != UInt.MAX_VALUE)
            counter++
        }
    }
}

fun providerEnvelopeDigest(envelope: ProviderCredentialEnvelopeV1): String =
    encodeCanonicalBase64Url(pairingSha256(ProviderCredentialWireCodec.encodeEnvelope(envelope)))

fun providerBindingDigest(binding: WearProviderBindingMetadataV1): String =
    encodeCanonicalBase64Url(pairingSha256(ProviderCredentialWireCodec.encodeBinding(binding)))

fun PairingHpke.encryptProviderCredential(recipient: HpkePublicKeyBase64Url, value: ProviderCredentialPlaintextV1): ProviderCredentialEnvelopeV1 {
    val raw = ProviderCredentialWireCodec.encodePlaintext(value)
    val encrypted = try { encrypt(recipient, raw, ProviderCredentialContextV1.bytes(value.targetDeviceId, value.providerConfigId, value.credentialRevision)) }
        finally { raw.fill(0) }
    return ProviderCredentialEnvelopeV1(targetDeviceId = value.targetDeviceId, providerConfigId = value.providerConfigId,
        credentialRevision = value.credentialRevision, encapsulatedKeyBase64Url = encrypted.encapsulatedKeyBase64Url, ciphertextBase64Url = encrypted.ciphertextBase64Url)
}

enum class ProviderCredentialRejection {
    CREDENTIAL_AUTHENTICATION_FAILED, INVALID_CREDENTIAL_ENVELOPE, TARGET_MISMATCH, PROVIDER_BINDING_MISMATCH,
    PROVISIONING_APPROVAL_REQUIRED, ROLLBACK_REJECTED, UNRESERVED_REVISION, WRONG_PROVISIONER,
    CREDENTIAL_INTEGRITY_CONFLICT, CREDENTIAL_STATE_LOST, CREDENTIAL_UNAVAILABLE, REVISION_OVERFLOW, SECURE_STORE_UNAVAILABLE,
}
sealed interface DecryptProviderCredentialResult {
    data class Authenticated(val plaintext: ProviderCredentialPlaintextV1) : DecryptProviderCredentialResult
    data class Rejected(val reason: ProviderCredentialRejection) : DecryptProviderCredentialResult
}

/** No storage/import/approval side effects. All plaintext validation happens after authentication. */
fun PairingHpke.decryptProviderCredential(key: PairingPrivateKeyMaterial, envelope: ProviderCredentialEnvelopeV1, target: DeviceId): DecryptProviderCredentialResult {
    if (target != envelope.targetDeviceId) return DecryptProviderCredentialResult.Rejected(ProviderCredentialRejection.TARGET_MISMATCH)
    val bytes = try {
        decrypt(key, envelope.encapsulatedKeyBase64Url, envelope.ciphertextBase64Url,
            ProviderCredentialContextV1.bytes(envelope.targetDeviceId, envelope.providerConfigId, envelope.credentialRevision))
    } catch (_: Exception) { return DecryptProviderCredentialResult.Rejected(ProviderCredentialRejection.CREDENTIAL_AUTHENTICATION_FAILED) }
    val value = try { ProviderCredentialWireCodec.decodePlaintext(bytes) } finally { bytes.fill(0) }
    if (value !is ProviderCredentialDecodeResult.Accepted) return DecryptProviderCredentialResult.Rejected(ProviderCredentialRejection.INVALID_CREDENTIAL_ENVELOPE)
    val inner = value.value
    if (inner.targetDeviceId != envelope.targetDeviceId || inner.providerConfigId != envelope.providerConfigId || inner.credentialRevision != envelope.credentialRevision)
        return DecryptProviderCredentialResult.Rejected(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH)
    return DecryptProviderCredentialResult.Authenticated(inner)
}

internal fun credentialU32(value: UInt): ByteArray = ByteArray(4) { (value shr (24 - it * 8)).toByte() }
internal fun credentialU64(value: ULong): ByteArray = ByteArray(8) { (value shr (56 - it * 8)).toByte() }
internal fun credentialLP(value: String): ByteArray = value.encodeToByteArray().let { credentialU32(it.size.toUInt()) + it }
