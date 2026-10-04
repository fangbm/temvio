package dev.agenticscheduler.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Independent SYN-018 protocol. Never a workspace SyncPayload. IDs retain D8 semantics. */
@Serializable
data class ProviderCredentialEnvelopeV1(
    val providerCredentialEnvelopeVersion: Int = 1,
    val targetDeviceId: DeviceId,
    val providerConfigId: String,
    val credentialRevision: Long,
    val encapsulatedKeyBase64Url: String,
    val ciphertextBase64Url: String,
) {
    init {
        require(providerCredentialEnvelopeVersion == 1)
        credentialIdentity(providerConfigId, credentialRevision)
        boundedBase64(encapsulatedKeyBase64Url, 32, 32)
        boundedBase64(ciphertextBase64Url, 16, 8208)
    }
}

@Serializable
data class ProviderCredentialPlaintextV1(
    val providerCredentialEnvelopeVersion: Int = 1,
    val targetDeviceId: DeviceId,
    val providerConfigId: String,
    val credentialRevision: Long,
    val credentialSecretBase64Url: String,
) {
    init {
        require(providerCredentialEnvelopeVersion == 1)
        credentialIdentity(providerConfigId, credentialRevision)
        val raw = boundedBase64(credentialSecretBase64Url, 1, 4096)
        try { require(raw.all { it.toInt() in 0x21..0x7e }) } finally { raw.fill(0) }
    }
    override fun toString(): String = "ProviderCredentialPlaintextV1(REDACTED)"
}

@Serializable enum class ProviderCredentialAcknowledgementResult { INSTALLED, DUPLICATE }

@Serializable
data class ProviderCredentialAcknowledgementV1(
    val providerCredentialAcknowledgementVersion: Int = 1,
    val targetDeviceId: DeviceId,
    val providerConfigId: String,
    val credentialRevision: Long,
    val envelopeDigestBase64Url: String,
    val result: ProviderCredentialAcknowledgementResult,
) {
    init {
        require(providerCredentialAcknowledgementVersion == 1)
        credentialIdentity(providerConfigId, credentialRevision)
        boundedBase64(envelopeDigestBase64Url, 32, 32)
    }
}

/** Local comparison transcript only: never upload this value or its digest to the relay. */
@Serializable
data class WearProviderBindingMetadataV1(
    val bindingVersion: Int = 1,
    val providerConfigId: String,
    val adapterProfile: String = "OPENAI_COMPATIBLE_CHAT_TOOLS",
    val baseUrl: String,
    val model: String,
    val maxContextUnits: Long,
    val reservedOutputUnits: Long,
    val streamingSupported: Boolean,
    val toolCallingSupported: Boolean,
    val credentialRequired: Boolean,
) {
    init {
        require(bindingVersion == 1 && adapterProfile == "OPENAI_COMPATIBLE_CHAT_TOOLS")
        MutationId(providerConfigId)
        require(baseUrl.isNotBlank() && model.isNotBlank())
        require(maxContextUnits > 0 && reservedOutputUnits > 0 && reservedOutputUnits < maxContextUnits)
        require(!credentialRequired || baseUrl.startsWith("https://"))
    }
}

enum class ProviderCredentialWireFailure {
    UNSUPPORTED_CREDENTIAL_ENVELOPE_VERSION, INVALID_CREDENTIAL_ENVELOPE, CREDENTIAL_ENVELOPE_TOO_LARGE,
}
sealed interface ProviderCredentialDecodeResult<out T> {
    data class Accepted<T>(val value: T) : ProviderCredentialDecodeResult<T>
    data class Rejected(val reason: ProviderCredentialWireFailure) : ProviderCredentialDecodeResult<Nothing>
}

object ProviderCredentialWireCodec {
    const val MAX_ENVELOPE_BYTES = 16384
    const val MAX_PLAINTEXT_BYTES = 8192
    const val MAX_HTTP_BODY_BYTES = 32768
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val routing = setOf("providerCredentialEnvelopeVersion", "targetDeviceId", "providerConfigId", "credentialRevision")
    private val envelopeKeys = routing + setOf("encapsulatedKeyBase64Url", "ciphertextBase64Url")
    private val plaintextKeys = routing + "credentialSecretBase64Url"
    private val ackKeys = setOf("providerCredentialAcknowledgementVersion", "targetDeviceId", "providerConfigId", "credentialRevision", "envelopeDigestBase64Url", "result")
    private val bindingKeys = setOf("bindingVersion", "providerConfigId", "adapterProfile", "baseUrl", "model", "maxContextUnits", "reservedOutputUnits", "streamingSupported", "toolCallingSupported", "credentialRequired")

    fun encodeEnvelope(value: ProviderCredentialEnvelopeV1): ByteArray = checkedEncode(json.encodeToString(value), MAX_ENVELOPE_BYTES)
    fun encodePlaintext(value: ProviderCredentialPlaintextV1): ByteArray = checkedEncode(json.encodeToString(value), MAX_PLAINTEXT_BYTES)
    fun encodeAcknowledgement(value: ProviderCredentialAcknowledgementV1): ByteArray = checkedEncode(json.encodeToString(value), MAX_HTTP_BODY_BYTES)
    fun encodeBinding(value: WearProviderBindingMetadataV1): ByteArray = checkedEncode(json.encodeToString(value), MAX_HTTP_BODY_BYTES)

    fun decodeEnvelope(bytes: ByteArray): ProviderCredentialDecodeResult<ProviderCredentialEnvelopeV1> =
        decode(bytes, MAX_ENVELOPE_BYTES, envelopeKeys, "providerCredentialEnvelopeVersion") { json.decodeFromJsonElement<ProviderCredentialEnvelopeV1>(it) }
    fun decodePlaintext(bytes: ByteArray): ProviderCredentialDecodeResult<ProviderCredentialPlaintextV1> =
        decode(bytes, MAX_PLAINTEXT_BYTES, plaintextKeys, "providerCredentialEnvelopeVersion") { json.decodeFromJsonElement<ProviderCredentialPlaintextV1>(it) }
    fun decodeAcknowledgement(bytes: ByteArray): ProviderCredentialDecodeResult<ProviderCredentialAcknowledgementV1> =
        decode(bytes, MAX_HTTP_BODY_BYTES, ackKeys, "providerCredentialAcknowledgementVersion") { json.decodeFromJsonElement<ProviderCredentialAcknowledgementV1>(it) }
    fun decodeBinding(bytes: ByteArray): ProviderCredentialDecodeResult<WearProviderBindingMetadataV1> =
        decode(bytes, MAX_HTTP_BODY_BYTES, bindingKeys, "bindingVersion") { json.decodeFromJsonElement<WearProviderBindingMetadataV1>(it) }

    private fun checkedEncode(text: String, cap: Int): ByteArray {
        require(validUnicode(text))
        return text.encodeToByteArray().also { require(it.size <= cap) }
    }

    private fun <T> decode(bytes: ByteArray, cap: Int, keys: Set<String>, versionKey: String, build: (JsonObject) -> T): ProviderCredentialDecodeResult<T> {
        if (bytes.size > cap) return ProviderCredentialDecodeResult.Rejected(ProviderCredentialWireFailure.CREDENTIAL_ENVELOPE_TOO_LARGE)
        return try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            require(!text.startsWith('\uFEFF') && validUnicode(text) && StrictJsonObjectKeys.hasNoDuplicateKeys(text))
            val root = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException()
            require(root.keys == keys)
            // Check decoded strings too: escaped unpaired surrogates must not survive parsing.
            root.values.forEach { require(it is JsonPrimitive && it != JsonNull && (!it.isString || validUnicode(it.content))) }
            val integers = setOf(versionKey, "credentialRevision", "maxContextUnits", "reservedOutputUnits")
            val booleans = setOf("streamingSupported", "toolCallingSupported", "credentialRequired")
            for ((key, value) in root) {
                val primitive = value as JsonPrimitive
                when (key) {
                    in integers -> require(!primitive.isString)
                    in booleans -> require(!primitive.isString && primitive.content in setOf("true", "false"))
                    else -> require(primitive.isString)
                }
            }
            val version = integer(root, versionKey)
            if (version != 1L) return ProviderCredentialDecodeResult.Rejected(ProviderCredentialWireFailure.UNSUPPORTED_CREDENTIAL_ENVELOPE_VERSION)
            listOf("credentialRevision", "maxContextUnits", "reservedOutputUnits").filter { it in keys }.forEach { integer(root, it) }
            ProviderCredentialDecodeResult.Accepted(build(root))
        } catch (_: CharacterCodingException) {
            ProviderCredentialDecodeResult.Rejected(ProviderCredentialWireFailure.INVALID_CREDENTIAL_ENVELOPE)
        } catch (_: IllegalArgumentException) {
            ProviderCredentialDecodeResult.Rejected(ProviderCredentialWireFailure.INVALID_CREDENTIAL_ENVELOPE)
        } catch (_: SerializationException) {
            ProviderCredentialDecodeResult.Rejected(ProviderCredentialWireFailure.INVALID_CREDENTIAL_ENVELOPE)
        }
    }

    private fun integer(root: JsonObject, key: String): Long {
        val p = root[key] as? JsonPrimitive ?: throw IllegalArgumentException()
        require(!p.isString && Regex("[1-9][0-9]*").matches(p.content))
        return p.content.toLong()
    }
}

private fun credentialIdentity(config: String, revision: Long) {
    MutationId(config)
    require(revision > 0)
}

private fun boundedBase64(text: String, min: Int, max: Int): ByteArray {
    require(text.length <= (max * 8 + 5) / 6)
    return decodeCanonicalBase64Url(text, null, "credential wire bytes").also { require(it.size in min..max) }
}

private fun validUnicode(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (c.isHighSurrogate()) { if (i == value.length || !value[i++].isLowSurrogate()) return false }
        else if (c.isLowSurrogate()) return false
    }
    return true
}
