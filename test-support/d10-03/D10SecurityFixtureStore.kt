package dev.agenticscheduler.fixtures

import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.sync.*

/** Test-only memory store; not evidence for DPAPI/Keystore and never a production fallback. */
class D10SecurityFixtureStore:PlatformD8SecureStore {
    var rejectAmk=false
    val public=HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    private var counter=0
    private val values=mutableMapOf<SecretReference,ByteArray>()
    private val credentials=mutableMapOf<SecretReference,DeviceCredential>()
    private fun put(bytes:ByteArray)=SecretReference("fixture://material/${counter++}").also {values[it]=bytes.copyOf()}
    override suspend fun importSecret(material:PlatformSecretMaterial)=put(material.copyRawSecretBytesForSecureStore())
    override suspend fun readSecret(reference:SecretReference):PlatformSecretMaterial?=values[reference]?.let {Raw(it)}
    override suspend fun delete(reference:SecretReference) {values.remove(reference);credentials.remove(reference)}
    override suspend fun importContentKey(material:ImportedContentKeyMaterial):ImportedContentKey {val raw=material.copyRawSecretBytesForSecureStore();return ImportedContentKey(put(raw),ContentKeyIdentity.fromRawAes256Key(raw))}
    override suspend fun generateContentKey()=importContentKey(Raw(ByteArray(32) {2}))
    override suspend fun contentAead(reference:SecretReference):SyncPayloadAead?=null
    override suspend fun generate():GeneratedDeviceCredential {val credential=DeviceCredential(public.value);return GeneratedDeviceCredential(store(credential),DeviceCredentialHashing.sha256Base64Url(credential))}
    override suspend fun store(value:DeviceCredential)=SecretReference("fixture://credential/${counter++}").also {credentials[it]=value}
    override suspend fun load(reference:SecretReference)=credentials[reference]
    override suspend fun generatePairingDeviceKey()=PersistedPairingDeviceKey(public,put(ByteArray(32) {4}))
    override suspend fun privateKey(reference:SecretReference):PairingPrivateKeyMaterial?=null
    override suspend fun importAccountMasterKey(material:PairingEphemeralKeyMaterial)=if(rejectAmk) null else put(material.copyRawKeyBytesForPairing())
    override suspend fun generateAccountMasterKey()=put(ByteArray(32) {5})
    override suspend fun exportAccountMasterKeyForPairing(reference:SecretReference):PairingEphemeralKeyMaterial?=values[reference]?.let {Raw(it)}
    override suspend fun exportContentKeyForPairing(reference:SecretReference):ExportedPairingContentKey?=values[reference]?.let {ExportedPairingContentKey(Raw(it),ContentKeyIdentity.fromRawAes256Key(it))}
    private class Raw(private val bytes:ByteArray):ImportedContentKeyMaterial,PairingEphemeralKeyMaterial {
        override fun copyRawSecretBytesForSecureStore()=bytes.copyOf()
        override fun copyRawKeyBytesForPairing()=bytes.copyOf()
    }
}
