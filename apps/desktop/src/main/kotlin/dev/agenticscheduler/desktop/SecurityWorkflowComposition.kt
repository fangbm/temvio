package dev.agenticscheduler.desktop

import dev.agenticscheduler.presentation.SecurityWorkflowServices
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.*
import io.ktor.client.HttpClient

/** Manual platform composition only. Screens receive services, never transport/key stores. */
internal fun desktopSecurityWorkflows(
    database: AgenticSchedulerDatabase, store: PlatformD8SecureStore,
    client: HttpClient, ids: UuidV7Generator, configuration: ActiveSyncRuntimeConfiguration,
    afterEnrollment: suspend () -> Unit,
): SecurityWorkflowServices {
    val enrollments = RoomLocalEnrollmentRepository(database)
    val ring = RoomSyncKeyMetadataRepository(database)
    val transactions = RoomApplicationTransactionRunner(database)
    val transport = KtorSyncLifecycleTransport(client,configuration.serverBaseUrl,{
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull()
        active?.takeIf {it.accountId==configuration.accountId}?.let {store.load(it.deviceCredentialReference)}
    })
    val recovery = RecoveryEnrollmentService(transport,LocalEnrollmentRequestService(enrollments,store,store),
        enrollments,store,store,SyncKeyPackageInstaller(store,ring),transactions)
    val rotation = RevocationRotationService(transport,enrollments,ring,store,store,store,store,
        RotationKeyPackageBuilder(ring,store,TinkPairingHpke()),store,transactions)
    return SecurityWorkflowServices(configuration.accountId,recovery,rotation,
        activeDeviceIds={transport.activeDevices().map {DeviceId(it.deviceId)}},
        newDeviceId={DeviceId(ids.next())},newRequestId={EnrollmentRequestId(ids.next())},newRotationId=ids::next,
        existingPendingIdentity={(enrollments.state(configuration.accountId) as? LocalEnrollmentState.Pending)?.let {it.deviceId to it.enrollmentRequestId}},
        afterEnrollment=afterEnrollment)
}
