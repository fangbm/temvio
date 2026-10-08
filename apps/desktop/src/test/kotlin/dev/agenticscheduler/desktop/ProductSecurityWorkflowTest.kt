package dev.agenticscheduler.desktop

import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.fixtures.D10SecurityFixtureStore
import dev.agenticscheduler.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

/** Actual platform composition + Ktor lifecycle adapter + Application services + Room.
 * Only HTTP peer and platform secret persistence are deterministic test doubles. */
class ProductSecurityWorkflowTest {
    @Test fun recoveryActivatesOnlyFromExplicitActionAndSecretNeverEntersScreenFacts() = fixture {f -> runBlocking {
        val c=SecurityWorkflowCoordinator(f.services)
        check(f.requests.isEmpty());check(f.enrollments.states().isEmpty())
        c.recoverFromUser(f.secret)
        check(c.message!!.startsWith("Recovery enrollment activated"));check(f.activated==1)
        val active=f.enrollments.states().single() as LocalEnrollmentState.Active
        check(active.syncSpaceId==f.space);check(f.ring.currentEncryptionKey(f.space)!!.keyEpoch==5L)
        check(!c.message!!.contains(f.secret.value));check(!c.message!!.contains("fixture://"))
        val recoveryRequest=f.requests.first {it.first.endsWith("/enroll")}.second
        check(!recoveryRequest.contains(f.secret.value));check(!recoveryRequest.contains("PRIVATE_DATA_CANARY"))
    } }
    @Test fun unavailableBootstrapAndInvalidSecretDoNotPublishOrClaimSuccess() = fixture {f -> runBlocking {
        val c=SecurityWorkflowCoordinator(f.services)
        c.recoverFromUser(RecoverySecret(encodeCanonicalBase64Url(ByteArray(32) {77})))
        check(c.message!!.contains("EnvelopeAuthenticationFailed"));check(f.enrollments.states().isEmpty());check(f.activated==0)
        f.offline=true;c.recoverFromUser(f.secret)
        check(c.message!!.contains("BootstrapFailed"));check(!c.message!!.contains("PRIVATE_DATA_CANARY"))
        check(f.enrollments.states().isEmpty())
    } }
    @Test fun recoveryReusesDurablePendingIdentityAfterCoordinatorRestart() = fixture {f -> runBlocking {
        f.store.rejectAmk=true
        val first=SecurityWorkflowCoordinator(f.services);first.recoverFromUser(f.secret)
        val pending=f.enrollments.states().single() as LocalEnrollmentState.Pending
        val attempted=f.requests.last {it.first.endsWith("/enroll")}.second
        check(first.message!!.contains("AccountMasterKeyImportFailed"))
        f.store.rejectAmk=false
        val next=SecurityWorkflowCoordinator(f.services);next.recoverFromUser(f.secret)
        val active=f.enrollments.states().single() as LocalEnrollmentState.Active
        check(active.deviceId==pending.deviceId);check(active.enrollmentRequestId==pending.enrollmentRequestId)
        check(attempted==f.requests.last {it.first.endsWith("/enroll")}.second);check(f.activated==1)
    } }
    @Test fun activationFailurePreservesCommittedEnrollmentAndReportsGuardedRuntime() = fixture {f -> runBlocking {
        f.activationFailure=true
        val c=SecurityWorkflowCoordinator(f.services);c.recoverFromUser(f.secret)
        check(f.enrollments.states().single() is LocalEnrollmentState.Active)
        check(c.message!!.contains("Enrollment activated, but runtime activation is unavailable"))
        check(!c.message!!.contains("PRIVATE_DATA_CANARY"))
    } }
    @Test fun revocationRequiresConfirmationAndUncertainSubmitRetriesExactPreparedBody() = fixture {f -> runBlocking {
        val c=SecurityWorkflowCoordinator(f.services);c.recoverFromUser(f.secret);f.requests.clear()
        c.revokeFromUser(f.other,f.secret,false);check(f.requests.isEmpty())
        c.refreshDevices();check(c.devices.size==2)
        f.failRotation=true;c.revokeFromUser(f.other,f.secret,true)
        check(c.hasRotationRetry);check(c.message!!.contains("uncertain"))
        val first=f.requests.last {it.first.endsWith("revoke-and-rotate")}.second
        val count=f.requests.size;c.revokeFromUser(f.other,f.secret,true);check(f.requests.size==count)
        f.failRotation=false;c.retryRotationFromUser()
        check(!c.hasRotationRetry);check(c.message!!.contains("Previously possessed material is not erased"))
        check(first==f.requests.last {it.first.endsWith("revoke-and-rotate")}.second)
        check(f.ring.currentEncryptionKey(f.space)!!.keyEpoch==6L)
        check(f.ring.historicalDecryptKeys(f.space).single().keyEpoch==5L)
        check(!first.contains(f.secret.value));check(!c.message!!.contains(f.secret.value))
    } }
    @Test fun selfRevocationIsRejectedAndOfflineDirectoryIsRedacted() = fixture {f -> runBlocking {
        val c=SecurityWorkflowCoordinator(f.services);c.recoverFromUser(f.secret)
        val self=(f.enrollments.states().single() as LocalEnrollmentState.Active).deviceId
        val count=f.requests.size;c.revokeFromUser(self,f.secret,true)
        check(c.message!!.contains("InvalidTarget"));check(f.requests.size==count)
        f.offline=true;c.refreshDevices();check(c.message!!.contains("No secret-bearing error"));check(!c.message!!.contains("PRIVATE_DATA_CANARY"))
    } }
    private fun fixture(test:(Fixture)->Unit) {
        val file=File.createTempFile("d10-security-",".db");val f=Fixture(file)
        try {test(f)} finally {f.client.close();f.db.close();file.delete()}
    }
    private class Fixture(file:File) {
        val db=openDesktopDatabase(file.absolutePath)
        val account=AccountId("synthetic-security-account");val space=SyncSpaceId("synthetic-security-space");val other=DeviceId("synthetic-other-device")
        val secret=RecoverySecret(encodeCanonicalBase64Url(ByteArray(32) { (it+17).toByte() }))
        val store=D10SecurityFixtureStore()
        val enrollments=RoomLocalEnrollmentRepository(db);val ring=RoomSyncKeyMetadataRepository(db)
        var offline=false;var failRotation=false;var activationFailure=false;var activated=0
        val requests=mutableListOf<Pair<String,String>>()
        private val envelope=encodeCanonicalBase64Url(RecoveryWireCodec.encodeEnvelope(RecoveryEnvelopeCodec.seal(secret,
            RecoveryEnvelopePlaintextV1(accountId=account,keyEpoch=5,accountMasterKeyBase64Url=encodeCanonicalBase64Url(ByteArray(32) {3}),syncSpace=RecoverySyncSpaceKeyRingV1(space,5,
                encodeCanonicalBase64Url(ByteArray(32) {1}),emptyList())))).encodeToByteArray())
        val client=HttpClient(MockEngine {request ->
            if(offline) error("PRIVATE_DATA_CANARY")
            val path=request.url.encodedPath;val body=request.body.toByteArray().decodeToString();requests+=path to body
            val response=when {
                path.endsWith("/bootstrap") -> """{"counter":5,"recoveryEnvelopeBase64Url":"$envelope"}"""
                path.endsWith("/enroll") -> {
                    val input=Json.parseToJsonElement(body).jsonObject
                    """{"accountId":"${input["accountId"]!!.jsonPrimitive.content}","deviceId":"${input["targetDeviceId"]!!.jsonPrimitive.content}"}"""
                }
                path.endsWith("/active") -> {
                    val self=(enrollments.states().single() as LocalEnrollmentState.Active).deviceId.value
                    """[{"deviceId":"$self","hpkePublicKeyBase64Url":"${store.public.value}"},{"deviceId":"${other.value}","hpkePublicKeyBase64Url":"${store.public.value}"}]"""
                }
                path.endsWith("revoke-and-rotate") -> if(failRotation) return@MockEngine respond("{}",HttpStatusCode.ServiceUnavailable,headersOf(HttpHeaders.ContentType,"application/json")) else "{}"
                else -> error("Unexpected lifecycle path")
            }
            respond(response,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        private var sequence=500
        val services=desktopSecurityWorkflows(db,store,client,object:UuidV7Generator {override fun next()="00000000-0000-7000-8000-${(sequence++).toString().padStart(12,'0')}"},
            ActiveSyncRuntimeConfiguration(account,"https://synthetic.invalid")) {activated++;if(activationFailure) error("PRIVATE_DATA_CANARY")}
    }
}
