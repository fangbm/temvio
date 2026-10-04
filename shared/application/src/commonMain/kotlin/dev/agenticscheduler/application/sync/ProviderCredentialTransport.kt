package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

interface ProviderCredentialTransport {
    suspend fun publish(request: ProviderCredentialReservationRequestV1)
    suspend fun assignedRequest(): ProviderCredentialMailboxDeliveryV1?
    suspend fun uploadExact(canonicalEnvelope: ByteArray)
    suspend fun fetch(config: String): ProviderCredentialMailboxDeliveryV1?
    suspend fun acknowledge(value: ProviderCredentialAcknowledgementV1)
}
class ProviderCredentialTransportException(val status: Int, val code: String) : IllegalStateException(code) {
    val retryable: Boolean get() = status == 408 || status == 429 || status >= 500
}

/** Dedicated authenticated HTTPS API; no workspace state, consent, causal clocks or crypto replacement. */
class KtorProviderCredentialTransport(private val client: HttpClient, baseUrl: String,
    private val deviceCredential: suspend () -> DeviceCredential?) : ProviderCredentialTransport {
    private val url = baseUrl.trimEnd('/') + "/v1/provider-credentials"
    init { require(baseUrl.startsWith("https://")) }
    override suspend fun publish(request: ProviderCredentialReservationRequestV1) { send("requests", ProviderCredentialMailboxWireCodec.encodeRequest(request)) }
    override suspend fun uploadExact(canonicalEnvelope: ByteArray) {
        val e = ProviderCredentialWireCodec.decodeEnvelope(canonicalEnvelope)
        require(e is ProviderCredentialDecodeResult.Accepted && ProviderCredentialWireCodec.encodeEnvelope(e.value).contentEquals(canonicalEnvelope))
        send("envelopes", canonicalEnvelope)
    }
    override suspend fun acknowledge(value: ProviderCredentialAcknowledgementV1) { send("acknowledgements", ProviderCredentialWireCodec.encodeAcknowledgement(value)) }
    override suspend fun assignedRequest(): ProviderCredentialMailboxDeliveryV1? = get("requests/assigned")
    override suspend fun fetch(config: String): ProviderCredentialMailboxDeliveryV1? { MutationId(config); return get("mailbox/${encodePathSegment(config)}") }
    private suspend fun send(path: String, bytes: ByteArray) {
        require(bytes.size <= ProviderCredentialWireCodec.MAX_HTTP_BODY_BYTES)
        // Scoped streaming prevents Ktor's default saved-body allocation before our cap check.
        client.preparePost("$url/$path") { authorization(); contentType(ContentType.Application.Json); setBody(bytes) }
            .execute { response -> checkResponse(response) }
    }
    private suspend fun get(path: String): ProviderCredentialMailboxDeliveryV1? {
        return client.prepareGet("$url/$path") { authorization() }.execute { response ->
            checkResponse(response)
            if (response.status == HttpStatusCode.NoContent) null
            else ProviderCredentialMailboxWireCodec.decodeDelivery(bounded(response)) ?: throw ProviderCredentialTransportException(0, "INVALID_CREDENTIAL_RESPONSE")
        }
    }
    private suspend fun checkResponse(response: HttpResponse) {
        if (response.status.value in 200..299) return
        val bytes = bounded(response)
        // Error details are an allowlisted protocol code, never relay-supplied credential/body text.
        val code = when (response.status.value) {
            401, 403 -> "UNAUTHORIZED"; 409 -> "DELIVERY_CONFLICT"; 410 -> "DELIVERY_EXPIRED"; else -> "HTTP_${response.status.value}"
        }
        bytes.fill(0)
        throw ProviderCredentialTransportException(response.status.value, code)
    }
    private suspend fun bounded(response: HttpResponse): ByteArray {
        val cap = ProviderCredentialWireCodec.MAX_HTTP_BODY_BYTES.toLong()
        if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > cap) throw ProviderCredentialTransportException(0, "CREDENTIAL_RESPONSE_TOO_LARGE")
        return response.bodyAsChannel().readRemaining(cap + 1).readByteArray().also { if (it.size > cap) throw ProviderCredentialTransportException(0, "CREDENTIAL_RESPONSE_TOO_LARGE") }
    }
    private suspend fun HttpRequestBuilder.authorization() {
        val value = deviceCredential() ?: throw ProviderCredentialTransportException(401, "MISSING_DEVICE_CREDENTIAL")
        header(HttpHeaders.Authorization, "Bearer ${value.value}")
    }
}

/** Source upload retry always reads the durable outbox first. This never automatically selects a Provider or credential. */
class ProviderCredentialSourceDeliveryService(private val repository: ProviderCredentialProvisioningRepository,
    private val hpke: PairingHpke, private val source: DeviceId, private val transport: ProviderCredentialTransport,
    private val activeDevices: suspend () -> List<ClientActiveDeviceDirectoryEntry>,
    private val nowEpochSeconds: () -> Long = { kotlin.time.Clock.System.now().epochSeconds }) {
    suspend fun prepareByExplicitLocalUser(delivery: ProviderCredentialMailboxDeliveryV1,
        sourceBinding: WearProviderBindingMetadataV1, credential: ByteArray): ProviderCredentialComparison {
        val request = delivery.request
        require(delivery.state == ProviderCredentialMailboxState.REQUESTED && delivery.envelope == null)
        if (delivery.expiresAtEpochSeconds <= nowEpochSeconds()) throw ProviderCredentialTransportException(410, "DELIVERY_EXPIRED")
        require(request.provisionerDeviceId == source && request.providerConfigId == sourceBinding.providerConfigId)
        validateProviderCredentialBytes(credential)
        val old = repository.delivery(source, request.targetDeviceId, request.providerConfigId, request.credentialRevision)
        // An explicit new selection cannot silently reuse a different prior transfer. Retry uses uploadReserved.
        if (old != null) throw ProviderProvisioningException(ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT)
        // Reuse the authenticated D8 ACTIVE directory/key; no provisioning-specific key or cached eligibility.
        val directory = activeDevices()
        require(directory.map { it.deviceId }.distinct().size == directory.size && directory.any { it.deviceId == source.value })
        val recipient = HpkePublicKeyBase64Url(requireNotNull(directory.singleOrNull { it.deviceId == request.targetDeviceId.value }).hpkePublicKeyBase64Url)
        val envelope = run {
            val plain = ProviderCredentialPlaintextV1(targetDeviceId = request.targetDeviceId, providerConfigId = request.providerConfigId,
                credentialRevision = request.credentialRevision, credentialSecretBase64Url = encodeCanonicalBase64Url(credential))
            val e = hpke.encryptProviderCredential(recipient, plain)
            repository.saveDelivery(ProviderCredentialDeliveryOutbox(source, e, ProviderCredentialWireCodec.encodeEnvelope(e).decodeToString(), providerEnvelopeDigest(e), ProviderDeliveryOutboxState.PREPARED, delivery.expiresAtEpochSeconds))
            e
        }
        return ProviderCredentialComparison(source, envelope, providerBindingDigest(sourceBinding), ProviderProvisioningSas.calculate(source, envelope, sourceBinding))
    }
    suspend fun comparisonForPendingDelivery(request: ProviderCredentialReservationRequestV1, sourceBinding: WearProviderBindingMetadataV1): ProviderCredentialComparison {
        require(request.provisionerDeviceId == source && request.providerConfigId == sourceBinding.providerConfigId)
        val row = repository.delivery(source, request.targetDeviceId, request.providerConfigId, request.credentialRevision)
            ?: throw ProviderProvisioningException(ProviderCredentialRejection.UNRESERVED_REVISION)
        require(row.state !in setOf(ProviderDeliveryOutboxState.DELIVERY_EXPIRED, ProviderDeliveryOutboxState.ACKNOWLEDGED))
        if (row.expiresAtEpochSeconds <= nowEpochSeconds()) {
            repository.removeDelivery(source, request.targetDeviceId, request.providerConfigId, request.credentialRevision)
            throw ProviderCredentialTransportException(410, "DELIVERY_EXPIRED")
        }
        return ProviderCredentialComparison(source, row.envelope, providerBindingDigest(sourceBinding), ProviderProvisioningSas.calculate(source, row.envelope, sourceBinding))
    }
    suspend fun uploadReserved(request: ProviderCredentialReservationRequestV1) {
        require(request.provisionerDeviceId == source)
        val row = repository.delivery(source, request.targetDeviceId, request.providerConfigId, request.credentialRevision)
            ?: throw ProviderProvisioningException(ProviderCredentialRejection.UNRESERVED_REVISION)
        if (row.state in setOf(ProviderDeliveryOutboxState.ACKNOWLEDGED, ProviderDeliveryOutboxState.DELIVERY_EXPIRED)) return
        if (row.expiresAtEpochSeconds <= nowEpochSeconds()) {
            repository.removeDelivery(source, request.targetDeviceId, request.providerConfigId, request.credentialRevision)
            throw ProviderCredentialTransportException(410, "DELIVERY_EXPIRED")
        }
        try {
            transport.uploadExact(row.canonicalEnvelopeJson.encodeToByteArray())
            // Cancellation/expiry/receipt may have removed it while HTTP was in flight; never recreate retry bytes.
            repository.markDeliveryUploaded(row)
        } catch (e: ProviderCredentialTransportException) {
            if (e.code == "DELIVERY_EXPIRED") repository.removeDelivery(source, request.targetDeviceId, request.providerConfigId, request.credentialRevision)
            throw e
        }
    }
    /** Receipt terminates encrypted retry retention only; it never authorizes target installation. */
    suspend fun receivedInformationalAcknowledgement(ack: ProviderCredentialAcknowledgementV1) {
        val row = repository.delivery(source, ack.targetDeviceId, ack.providerConfigId, ack.credentialRevision) ?: return
        if (row.envelopeDigest != ack.envelopeDigestBase64Url) throw ProviderProvisioningException(ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT)
        repository.removeDelivery(source, ack.targetDeviceId, ack.providerConfigId, ack.credentialRevision)
    }
    suspend fun cancelByExplicitLocalUser(request: ProviderCredentialReservationRequestV1) {
        require(request.provisionerDeviceId == source)
        repository.removeDelivery(source, request.targetDeviceId, request.providerConfigId, request.credentialRevision)
    }
}
