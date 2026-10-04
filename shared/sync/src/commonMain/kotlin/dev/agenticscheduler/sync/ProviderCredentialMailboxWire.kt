package dev.agenticscheduler.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Routing only. No binding contents/digest, SecretRef, Provider payload, cursor or causal clock. */
@Serializable
data class ProviderCredentialReservationRequestV1(
    val targetDeviceId: DeviceId,
    val providerConfigId: String,
    val credentialRevision: Long,
    val provisionerDeviceId: DeviceId,
) { init { MutationId(providerConfigId); require(credentialRevision > 0) } }

@Serializable enum class ProviderCredentialMailboxState { REQUESTED, DELIVERED, ACKNOWLEDGED, DELIVERY_EXPIRED }
@Serializable
data class ProviderCredentialMailboxDeliveryV1(
    val request: ProviderCredentialReservationRequestV1,
    val state: ProviderCredentialMailboxState,
    val expiresAtEpochSeconds: Long,
    val envelope: ProviderCredentialEnvelopeV1?,
) {
    init {
        require(expiresAtEpochSeconds > 0)
        require((state == ProviderCredentialMailboxState.DELIVERED) == (envelope != null))
        if (envelope != null) require(envelope.targetDeviceId == request.targetDeviceId && envelope.providerConfigId == request.providerConfigId && envelope.credentialRevision == request.credentialRevision)
    }
}

/** One routing identity per request. JSON has the same strict lexical and UTF-8 policy as C1. */
object ProviderCredentialMailboxWireCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun encodeDelivery(value: ProviderCredentialMailboxDeliveryV1): ByteArray = json.encodeToString(value).encodeToByteArray().also { require(it.size <= ProviderCredentialWireCodec.MAX_HTTP_BODY_BYTES) }
    fun decodeDelivery(bytes: ByteArray): ProviderCredentialMailboxDeliveryV1? = try {
        require(bytes.size <= ProviderCredentialWireCodec.MAX_HTTP_BODY_BYTES)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(StrictJsonObjectKeys.hasNoDuplicateKeys(text))
        val root = json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("request", "state", "expiresAtEpochSeconds", "envelope"))
        val request = requireNotNull(decodeRequest(root.getValue("request").toString().encodeToByteArray()))
        val expiry = root.getValue("expiresAtEpochSeconds").jsonPrimitive
        require(!expiry.isString && Regex("[1-9][0-9]*").matches(expiry.content))
        val envelope = if (root.getValue("envelope") == JsonNull) null else
            (ProviderCredentialWireCodec.decodeEnvelope(root.getValue("envelope").toString().encodeToByteArray()) as? ProviderCredentialDecodeResult.Accepted)?.value ?: throw IllegalArgumentException()
        if (envelope != null) require(envelope.targetDeviceId == request.targetDeviceId && envelope.providerConfigId == request.providerConfigId && envelope.credentialRevision == request.credentialRevision)
        val state = root.getValue("state").jsonPrimitive
        require(state.isString)
        ProviderCredentialMailboxDeliveryV1(request, ProviderCredentialMailboxState.valueOf(state.content), expiry.content.toLong(), envelope)
    } catch (_: CharacterCodingException) { null } catch (_: IllegalArgumentException) { null } catch (_: SerializationException) { null }
    fun encodeRequest(value: ProviderCredentialReservationRequestV1): ByteArray = json.encodeToString(value).encodeToByteArray().also { require(it.size <= ProviderCredentialWireCodec.MAX_HTTP_BODY_BYTES) }
    fun decodeRequest(bytes: ByteArray): ProviderCredentialReservationRequestV1? = try {
        require(bytes.size <= ProviderCredentialWireCodec.MAX_HTTP_BODY_BYTES)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(StrictJsonObjectKeys.hasNoDuplicateKeys(text))
        val root = json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("targetDeviceId", "providerConfigId", "credentialRevision", "provisionerDeviceId"))
        val revision = root.getValue("credentialRevision").jsonPrimitive
        require(!revision.isString && Regex("[1-9][0-9]*").matches(revision.content))
        // Reuse strict envelope Unicode/identity/revision validation through a harmless shaped envelope.
        val validation = buildJsonObject {
            put("providerCredentialEnvelopeVersion", 1)
            put("targetDeviceId", root.getValue("targetDeviceId"))
            put("providerConfigId", root.getValue("providerConfigId"))
            put("credentialRevision", revision)
            put("encapsulatedKeyBase64Url", encodeCanonicalBase64Url(ByteArray(32)))
            put("ciphertextBase64Url", encodeCanonicalBase64Url(ByteArray(16)))
        }
        require(ProviderCredentialWireCodec.decodeEnvelope(validation.toString().encodeToByteArray()) is ProviderCredentialDecodeResult.Accepted)
        val source = root.getValue("provisionerDeviceId").jsonPrimitive
        require(source.isString && source.content.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == source.content)
        json.decodeFromJsonElement<ProviderCredentialReservationRequestV1>(root)
    } catch (_: CharacterCodingException) { null } catch (_: IllegalArgumentException) { null } catch (_: SerializationException) { null }
}
