package dev.agenticscheduler.sync

import kotlin.test.*

class ProviderCredentialWireTest {
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader.getResourceAsStream(name)).use { it.readBytes() }
    private fun envelope() = (ProviderCredentialWireCodec.decodeEnvelope(fixture("credential-envelope.v1.json")) as ProviderCredentialDecodeResult.Accepted).value

    @Test fun `four positive canonical fixtures round trip exactly`() {
        assertContentEquals(fixture("credential-envelope.v1.json"), ProviderCredentialWireCodec.encodeEnvelope(envelope()))
        val plain = (ProviderCredentialWireCodec.decodePlaintext(fixture("credential-plaintext.v1.json")) as ProviderCredentialDecodeResult.Accepted).value
        assertContentEquals(fixture("credential-plaintext.v1.json"), ProviderCredentialWireCodec.encodePlaintext(plain))
        val binding = (ProviderCredentialWireCodec.decodeBinding(fixture("wear-provider-binding.v1.json")) as ProviderCredentialDecodeResult.Accepted).value
        assertContentEquals(fixture("wear-provider-binding.v1.json"), ProviderCredentialWireCodec.encodeBinding(binding))
        val ack = (ProviderCredentialWireCodec.decodeAcknowledgement(fixture("credential-ack.v1.json")) as ProviderCredentialDecodeResult.Accepted).value
        assertContentEquals(fixture("credential-ack.v1.json"), ProviderCredentialWireCodec.encodeAcknowledgement(ack))
        assertEquals("WearTarget:opaque-A_01", envelope().targetDeviceId.value)
        assertNotEquals(envelope().targetDeviceId, DeviceId(envelope().targetDeviceId.value.lowercase()))
    }
    @Test fun `negative syntax fixtures and lexical integers fail closed`() {
        listOf("unknown-version-envelope.json", "duplicate-field-envelope.v1.json", "invalid-revision.v1.json").forEach {
            assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodeEnvelope(fixture(it)))
        }
        val text = fixture("credential-envelope.v1.json").decodeToString()
        listOf("0", "-1", "1.0", "1e0", "\"1\"", "9223372036854775808", "null").forEach { revision ->
            assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodeEnvelope(text.replace("\"credentialRevision\":1", "\"credentialRevision\":$revision").encodeToByteArray()))
        }
        val max = text.replace("\"credentialRevision\":1", "\"credentialRevision\":9223372036854775807")
        assertEquals(Long.MAX_VALUE, (ProviderCredentialWireCodec.decodeEnvelope(max.encodeToByteArray()) as ProviderCredentialDecodeResult.Accepted).value.credentialRevision)
        listOf(text.dropLast(1) + ",\"secret\":\"x\"}", text.replace("\"credentialRevision\":1", "\"credentialRevision\":1,\"credential\\u0052evision\":1"),
            text.replace("WearTarget:opaque-A_01", "\\uD800"), "\uFEFF$text", text.replace(envelope().providerConfigId, "not-uuid"), text.replace(envelope().encapsulatedKeyBase64Url, envelope().encapsulatedKeyBase64Url + "=")
        ).forEach { assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodeEnvelope(it.encodeToByteArray())) }
    }
    @Test fun `UTF8 and allocation caps are checked before parsing`() {
        assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodeEnvelope(byteArrayOf(0xC0.toByte(), 0xAF.toByte())))
        assertEquals(ProviderCredentialWireFailure.CREDENTIAL_ENVELOPE_TOO_LARGE,
            (ProviderCredentialWireCodec.decodeEnvelope(ByteArray(16385)) as ProviderCredentialDecodeResult.Rejected).reason)
        assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodePlaintext(ByteArray(8193)))
    }
    @Test fun `credential bounds reject whitespace controls nonASCII and oversize`() {
        val plain = (ProviderCredentialWireCodec.decodePlaintext(fixture("credential-plaintext.v1.json")) as ProviderCredentialDecodeResult.Accepted).value
        listOf(1, 4096).forEach { size ->
            val valid = plain.copy(credentialSecretBase64Url = encodeCanonicalBase64Url(ByteArray(size) { 0x21 }))
            assertIs<ProviderCredentialDecodeResult.Accepted<*>>(ProviderCredentialWireCodec.decodePlaintext(ProviderCredentialWireCodec.encodePlaintext(valid)))
        }
        listOf(byteArrayOf(), ByteArray(4097) { 0x21 }, byteArrayOf(0x20), byteArrayOf(0x7f), byteArrayOf(0xc3.toByte(), 0xa9.toByte())).forEach { bad ->
            assertFailsWith<IllegalArgumentException> { plain.copy(credentialSecretBase64Url = encodeCanonicalBase64Url(bad)) }
        }
    }
    @Test fun `order whitespace accepted but base64 noncanonical tails and unexpected fields rejected`() {
        val e = envelope()
        assertFails { e.copy(targetDeviceId = DeviceId("\uD800")) }
        val reordered = "{ \"ciphertextBase64Url\":\"${e.ciphertextBase64Url}\",\"encapsulatedKeyBase64Url\":\"${e.encapsulatedKeyBase64Url}\",\"credentialRevision\":1,\"providerConfigId\":\"${e.providerConfigId}\",\"targetDeviceId\":\"${e.targetDeviceId.value}\",\"providerCredentialEnvelopeVersion\":1 }"
        assertEquals(e, (ProviderCredentialWireCodec.decodeEnvelope(reordered.encodeToByteArray()) as ProviderCredentialDecodeResult.Accepted).value)
        assertFails { e.copy(encapsulatedKeyBase64Url = "A".repeat(42) + "B") }
        assertFails { e.copy(ciphertextBase64Url = encodeCanonicalBase64Url(ByteArray(8209))) }
        assertFails { e.copy(ciphertextBase64Url = encodeCanonicalBase64Url(ByteArray(15))) }
        val binding = fixture("wear-provider-binding.v1.json").decodeToString()
        assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodeBinding((binding.dropLast(1) + ",\"secretReference\":\"local\"}").encodeToByteArray()))
        assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodeBinding(binding.replace("\"streamingSupported\":true", "\"streamingSupported\":\"true\"").encodeToByteArray()))
        val text = fixture("credential-envelope.v1.json").decodeToString()
        assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodeEnvelope(text.replace("\"WearTarget:opaque-A_01\"", "123").encodeToByteArray()))
    }
    @Test fun `mailbox routing retains exact opaque IDs and rejects unknown duplicate coercions and inconsistent payload state`() {
        val request = ProviderCredentialReservationRequestV1(envelope().targetDeviceId, envelope().providerConfigId, 1, DeviceId("PhoneSource:opaque-B_02"))
        val canonical = ProviderCredentialMailboxWireCodec.encodeRequest(request)
        assertEquals(request, ProviderCredentialMailboxWireCodec.decodeRequest(canonical))
        val text = canonical.decodeToString()
        listOf(text.dropLast(1) + ",\"bindingDigest\":\"x\"}", text.replace("\"credentialRevision\":1", "\"credentialRevision\":\"1\""),
            text.replace("\"credentialRevision\":1", "\"credentialRevision\":1,\"credentialRevision\":1"), text.replace("PhoneSource:opaque-B_02", "\\uD800")).forEach {
            assertNull(ProviderCredentialMailboxWireCodec.decodeRequest(it.encodeToByteArray()))
        }
        val delivery = ProviderCredentialMailboxDeliveryV1(request, ProviderCredentialMailboxState.DELIVERED, 604800, envelope())
        val encoded = ProviderCredentialMailboxWireCodec.encodeDelivery(delivery)
        assertEquals(delivery, ProviderCredentialMailboxWireCodec.decodeDelivery(encoded))
        assertNull(ProviderCredentialMailboxWireCodec.decodeDelivery(encoded.decodeToString().replace("DELIVERED", "REQUESTED").encodeToByteArray()))
        assertFails { delivery.copy(envelope = null) }
    }
}
