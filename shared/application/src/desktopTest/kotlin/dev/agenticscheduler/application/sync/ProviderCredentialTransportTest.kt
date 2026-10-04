package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ProviderCredentialTransportTest {
    private val credential = DeviceCredential("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    private val request = ProviderCredentialReservationRequestV1(DeviceId("WearTarget:opaque-A_01"), "00000000-0000-7000-8000-000000000101", 1, DeviceId("PhoneSource:opaque-B_02"))
    private val envelope = ProviderCredentialEnvelopeV1(targetDeviceId = request.targetDeviceId, providerConfigId = request.providerConfigId,
        credentialRevision = 1, encapsulatedKeyBase64Url = encodeCanonicalBase64Url(ByteArray(32)), ciphertextBase64Url = encodeCanonicalBase64Url(ByteArray(16)))

    @Test fun `dedicated endpoints authenticate and upload exact canonical bytes without binding or workspace metadata`() = runBlocking {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray>()
        val assigned = ProviderCredentialMailboxDeliveryV1(request, ProviderCredentialMailboxState.REQUESTED, 604800, null)
        val delivered = assigned.copy(state = ProviderCredentialMailboxState.DELIVERED, envelope = envelope)
        val client = HttpClient(MockEngine { data ->
            assertEquals("Bearer ${credential.value}", data.headers[HttpHeaders.Authorization])
            assertTrue(data.url.parameters.isEmpty())
            paths += data.url.encodedPath
            if (data.method == HttpMethod.Get) respond(ProviderCredentialMailboxWireCodec.encodeDelivery(if (data.url.encodedPath.endsWith("assigned")) assigned else delivered))
            else {
                bodies += (data.body as OutgoingContent.ByteArrayContent).bytes()
                respond("", HttpStatusCode.NoContent)
            }
        })
        try {
            val transport = KtorProviderCredentialTransport(client, "https://relay.example") { credential }
            transport.publish(request); assertEquals(assigned, transport.assignedRequest())
            val exact = ProviderCredentialWireCodec.encodeEnvelope(envelope)
            transport.uploadExact(exact); assertEquals(delivered, transport.fetch(request.providerConfigId))
            transport.acknowledge(ProviderCredentialAcknowledgementV1(targetDeviceId = request.targetDeviceId, providerConfigId = request.providerConfigId,
                credentialRevision = 1, envelopeDigestBase64Url = providerEnvelopeDigest(envelope), result = ProviderCredentialAcknowledgementResult.INSTALLED))
            assertContentEquals(exact, bodies[1])
            assertEquals(listOf("requests", "requests/assigned", "envelopes", "mailbox/${request.providerConfigId}", "acknowledgements"), paths.map { it.removePrefix("/v1/provider-credentials/") })
            for (body in bodies) for (forbidden in listOf("bindingDigest", "baseUrl", "model", "SecretRef", "syncSpaceId", "dvv", "cursor")) assertFalse(forbidden in body.decodeToString())
        } finally { client.close() }
    }

    @Test fun `missing auth and noncanonical upload reject before any HTTP call`() = runBlocking {
        var sent = 0
        val client = HttpClient(MockEngine { sent++; respond("", HttpStatusCode.NoContent) })
        try {
            val transport = KtorProviderCredentialTransport(client, "https://relay.example") { null }
            assertEquals("MISSING_DEVICE_CREDENTIAL", assertFailsWith<ProviderCredentialTransportException> { transport.publish(request) }.code)
            assertFailsWith<IllegalArgumentException> { transport.uploadExact((" " + ProviderCredentialWireCodec.encodeEnvelope(envelope).decodeToString()).encodeToByteArray()) }
            assertEquals(0, sent)
        } finally { client.close() }
    }

    @Test fun `response length and streamed body caps and redacted terminal retry status are enforced`() = runBlocking {
        for (contentLength in listOf(false, true)) {
            val client = HttpClient(MockEngine {
                respond(if (contentLength) "" else "x".repeat(32769), headers = if (contentLength) headersOf(HttpHeaders.ContentLength, "32769") else headersOf())
            })
            try {
                val transport = KtorProviderCredentialTransport(client, "https://relay.example") { credential }
                assertEquals("CREDENTIAL_RESPONSE_TOO_LARGE", assertFailsWith<ProviderCredentialTransportException> { transport.assignedRequest() }.code)
            } finally { client.close() }
        }
        for ((status, code, retry) in listOf(Triple(503, "HTTP_503", true), Triple(410, "DELIVERY_EXPIRED", false), Triple(409, "DELIVERY_CONFLICT", false), Triple(403, "UNAUTHORIZED", false))) {
            val client = HttpClient(MockEngine { respond("untrusted-body-secret-canary", HttpStatusCode.fromValue(status)) })
            try {
                val transport = KtorProviderCredentialTransport(client, "https://relay.example") { credential }
                val failure = assertFailsWith<ProviderCredentialTransportException> { transport.publish(request) }
                assertEquals(code, failure.code); assertEquals(retry, failure.retryable)
                assertFalse("untrusted-body-secret-canary" in failure.toString())
            } finally { client.close() }
        }
    }
}
