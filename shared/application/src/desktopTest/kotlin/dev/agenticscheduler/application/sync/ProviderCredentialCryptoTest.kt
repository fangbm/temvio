package dev.agenticscheduler.application.sync

import com.google.crypto.tink.*
import com.google.crypto.tink.hybrid.*
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import dev.agenticscheduler.sync.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import kotlin.test.*

class ProviderCredentialCryptoTest {
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader.getResourceAsStream(name)).use { it.readBytes() }
    private val vector get() = Json.parseToJsonElement(fixture("hpke-sas-vector.v1.json").decodeToString()).jsonObject
    private fun raw(name: String) = decodeCanonicalBase64Url(vector.getValue(name).jsonPrimitive.content, null, "public fixture")
    private fun envelope(name: String = "credential-envelope.v1.json") = (ProviderCredentialWireCodec.decodeEnvelope(fixture(name)) as ProviderCredentialDecodeResult.Accepted).value
    private fun binding() = (ProviderCredentialWireCodec.decodeBinding(fixture("wear-provider-binding.v1.json")) as ProviderCredentialDecodeResult.Accepted).value
    private fun fixedPrivate(hpke: TinkPairingHpke): PairingPrivateKeyMaterial {
        HybridConfig.register()
        val parameters = HpkeParameters.builder().setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256).setAeadId(HpkeParameters.AeadId.AES_256_GCM).setVariant(HpkeParameters.Variant.NO_PREFIX).build()
        val key = HpkePrivateKey.create(HpkePublicKey.create(parameters, Bytes.copyFrom(raw("recipientPublicKeyBase64Url")), null),
            SecretBytes.copyFrom(raw("recipientPrivateKeyBase64Url"), InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary()).build()
        val output = ByteArrayOutputStream()
        handle.write(BinaryKeysetWriter.withOutputStream(output), object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext
        })
        return hpke.restoreFromSecureStore(output.toByteArray())
    }
    @Test fun `committed vector really decrypts with existing Tink and exact context`() {
        val hpke = TinkPairingHpke(); val e = envelope(); val key = fixedPrivate(hpke)
        assertContentEquals(raw("contextInfoBase64Url"), ProviderCredentialContextV1.bytes(e.targetDeviceId, e.providerConfigId, e.credentialRevision))
        val result = assertIs<DecryptProviderCredentialResult.Authenticated>(hpke.decryptProviderCredential(key, e, e.targetDeviceId))
        assertContentEquals(fixture("credential-plaintext.v1.json"), ProviderCredentialWireCodec.encodePlaintext(result.plaintext))
        assertEquals(vector.getValue("expectedPlaintextSha256Base64Url").jsonPrimitive.content, encodeCanonicalBase64Url(pairingSha256(ProviderCredentialWireCodec.encodePlaintext(result.plaintext))))
        assertEquals(vector.getValue("canonicalEnvelopeSha256Base64Url").jsonPrimitive.content, providerEnvelopeDigest(e))
        assertEquals(vector.getValue("canonicalBindingSha256Base64Url").jsonPrimitive.content, providerBindingDigest(binding()))
    }
    @Test fun `committed SAS transcript and eight digits match exactly`() {
        val source = DeviceId(vector.getValue("provisionerDeviceId").jsonPrimitive.content)
        assertContentEquals(raw("sasTranscriptBase64Url"), ProviderProvisioningSas.transcript(source, envelope(), binding()))
        assertEquals("51070555", ProviderProvisioningSas.calculate(source, envelope(), binding()))
        assertEquals(vector.getValue("sasDigestBase64Url").jsonPrimitive.content, encodeCanonicalBase64Url(pairingSha256(ProviderProvisioningSas.transcript(source, envelope(), binding()) + byteArrayOf(0, 0, 0, 0))))
    }
    @Test fun `every routing context substitution tamper and wrong key rejects without importing`() {
        val hpke = TinkPairingHpke(); val e = envelope(); val key = fixedPrivate(hpke)
        listOf(envelope("tampered-envelope.v1.json"), envelope("wrong-target-envelope.v1.json"),
            e.copy(targetDeviceId = DeviceId(e.targetDeviceId.value.lowercase())), e.copy(providerConfigId = "00000000-0000-7000-8000-000000000199"), e.copy(credentialRevision = 2)).forEach {
            assertIs<DecryptProviderCredentialResult.Rejected>(hpke.decryptProviderCredential(key, it, it.targetDeviceId))
        }
        assertIs<DecryptProviderCredentialResult.Rejected>(hpke.decryptProviderCredential(hpke.generateDeviceKeyPair().privateKey, e, e.targetDeviceId))
        assertIs<ProviderCredentialDecodeResult.Rejected>(ProviderCredentialWireCodec.decodeEnvelope(fixture("unknown-version-envelope.json")))
    }
    @Test fun `authenticated inner routing mismatch still rejects`() {
        val hpke = TinkPairingHpke(); val recipient = hpke.generateDeviceKeyPair(); val e = envelope()
        val encrypted = hpke.encrypt(recipient.publicKey, fixture("mismatched-config-plaintext.v1.json"), ProviderCredentialContextV1.bytes(e.targetDeviceId, e.providerConfigId, e.credentialRevision))
        val mismatch = e.copy(encapsulatedKeyBase64Url = encrypted.encapsulatedKeyBase64Url, ciphertextBase64Url = encrypted.ciphertextBase64Url)
        assertEquals(DecryptProviderCredentialResult.Rejected(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH), hpke.decryptProviderCredential(recipient.privateKey, mismatch, e.targetDeviceId))
    }
    @Test fun `every SAS input changes comparison including binding only changes`() {
        val source = DeviceId("PhoneSource:opaque-B_02"); val e = envelope(); val b = binding()
        val baseline = ProviderProvisioningSas.calculate(source, e, b)
        assertNotEquals(baseline, ProviderProvisioningSas.calculate(DeviceId(source.value.lowercase()), e, b))
        listOf(e.copy(targetDeviceId = DeviceId(e.targetDeviceId.value.lowercase())), e.copy(credentialRevision = 2), envelope("tampered-envelope.v1.json")).forEach {
            assertNotEquals(baseline, ProviderProvisioningSas.calculate(source, it, b))
        }
        val config = "00000000-0000-7000-8000-000000000199"
        assertNotEquals(baseline, ProviderProvisioningSas.calculate(source, e.copy(providerConfigId = config), b.copy(providerConfigId = config)))
        listOf(b.copy(baseUrl = "https://other.example/v1"), b.copy(model = "other-model"), b.copy(maxContextUnits = 65536),
            b.copy(reservedOutputUnits = 2048), b.copy(streamingSupported = false), b.copy(toolCallingSupported = false), b.copy(credentialRequired = false)).forEach {
            assertNotEquals(baseline, ProviderProvisioningSas.calculate(source, e, it))
        }
    }
}
