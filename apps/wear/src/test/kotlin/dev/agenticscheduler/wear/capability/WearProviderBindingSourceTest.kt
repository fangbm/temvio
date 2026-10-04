package dev.agenticscheduler.wear.capability

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WearProviderBindingSourceTest {
    private val target = DeviceId("Watch-opaque")
    private val config = ProviderConfig(ProviderConfigId("01900000-0000-7000-8000-000000000001"), "https://provider.example/v1", "selected-model", 2048, 512, false, true, SecretReference("provider-slot"))
    private class Repository : ProviderCredentialProvisioningRepository {
        var current: ProviderCredentialRevisionState? = null
        var installs = emptyList<ProviderCredentialInstallJournal>()
        override suspend fun state(target: DeviceId, config: String) = current
        override suspend fun journals(target: DeviceId) = installs
        override suspend fun openConfig(target: DeviceId, config: String): ProviderCredentialRevisionState = error("read-only")
        override suspend fun compareAndSet(expected: ProviderCredentialRevisionState, updated: ProviderCredentialRevisionState, journals: List<ProviderCredentialInstallJournal>): Boolean = error("read-only")
        override suspend fun updateJournal(expected: ProviderCredentialInstallJournal, updated: ProviderCredentialInstallJournal): Boolean = error("read-only")
        override suspend fun saveDelivery(value: ProviderCredentialDeliveryOutbox): ProviderCredentialDeliveryOutbox = error("read-only")
        override suspend fun markDeliveryUploaded(expected: ProviderCredentialDeliveryOutbox): Boolean = error("read-only")
        override suspend fun delivery(source: DeviceId, target: DeviceId, config: String, revision: Long): ProviderCredentialDeliveryOutbox? = error("read-only")
        override suspend fun removeDelivery(source: DeviceId, target: DeviceId, config: String, revision: Long): Unit = error("read-only")
    }
    private class Secrets : PlatformProviderCredentialStore {
        var raw: ByteArray? = "test-only-credential".encodeToByteArray(); var reads = 0; var unavailable = false
        var afterRead: () -> Unit = {}; var lastCopy: ByteArray? = null
        override suspend fun readProviderSecret(reference: SecretReference): ByteArray? {
            reads++; if (unavailable) throw SecureStoreUnavailableException("TEST_UNAVAILABLE")
            return raw?.copyOf()?.also { lastCopy = it; afterRead() }
        }
        override suspend fun prepareProviderSlot(): PreparedProviderSecretSlot = error("read-only")
        override suspend fun restoreProviderSlot(reference: SecretReference, installIdentity: String): PreparedProviderSecretSlot? = error("read-only")
        override suspend fun importPreparedProviderSecret(slot: PreparedProviderSecretSlot, credential: ByteArray): Unit = error("read-only")
        override suspend fun deleteProviderSlot(slot: PreparedProviderSecretSlot): Unit = error("read-only")
    }
    private inner class Fixture {
        var local: ProviderConfig? = config; var active = true; var approvedFree = false
        val binding = WearProviderBinding(config.provisioningBinding(config.id, true), WearEndpointRoute.PUBLIC_HTTPS)
        val repository = Repository().apply {
            current = ProviderCredentialRevisionState(target, config.id.value, highestReservedRevision = 1, highestAcceptedRevision = 1,
                phase = ProviderReservationPhase.ACTIVE, approvedBinding = binding.metadata, activeBinding = binding.metadata,
                activeReference = config.credentialReference!!.value, activeInstallIdentity = "install-1")
            installs = listOf(ProviderCredentialInstallJournal(target, config.id.value, 1, config.credentialReference!!.value, "install-1", null, null, ProviderInstallPhase.METADATA_COMMITTED))
        }
        val secrets = Secrets()
        val source = WearProviderBindingSource({ local }, repository, secrets, target, { active }, { approvedFree })
    }
    @Test fun approvedActiveBindingReadsAndWipesActualSecretCopy() = runTest {
        val f = Fixture(); assertTrue(f.source.observe(f.binding).facts.providerReady); assertEquals(1, f.secrets.reads)
        assertTrue(f.secrets.lastCopy!!.all { it == 0.toByte() })
    }
    @Test fun nonNullReferenceWithMissingCorruptOrUnavailableSecretIsNotReady() = runTest {
        for (mode in 0..2) {
            val f = Fixture(); when (mode) { 0 -> f.secrets.raw = null; 1 -> f.secrets.raw = byteArrayOf(0); 2 -> f.secrets.unavailable = true }
            val facts = f.source.observe(f.binding).facts; assertFalse(facts.providerReady); assertFalse(facts.matchingSecretAvailable); assertFalse(facts.installBlocked)
        }
    }
    @Test fun preparedRecoveryCleanupDisabledAndInvalidOwnershipFailClosed() = runTest {
        for (mode in 0..4) {
            val f = Fixture()
            when (mode) {
                0 -> f.repository.installs += f.repository.installs.single().copy(installIdentity = "replacement", phase = ProviderInstallPhase.PREPARED_IMPORT)
                1 -> f.repository.installs += f.repository.installs.single().copy(installIdentity = "retired", phase = ProviderInstallPhase.CLEANUP_PENDING)
                2 -> f.repository.current = f.repository.current!!.copy(phase = ProviderReservationPhase.DISABLED)
                3 -> f.repository.current = f.repository.current!!.copy(rejectionFloor = ProviderLocalCounter.of(1))
                4 -> f.active = false
            }
            val facts = f.source.observe(f.binding).facts; assertTrue(facts.installBlocked); assertFalse(facts.providerReady); assertEquals(0, f.secrets.reads)
        }
    }
    @Test fun configEditAndWrongReferenceInvalidateExactBindingBeforeSecretRead() = runTest {
        for (mode in 0..1) {
            val f = Fixture(); f.local = if (mode == 0) config.copy(model = "new-model") else config.copy(credentialReference = SecretReference("wrong-slot"))
            assertFalse(f.source.observe(f.binding).facts.providerReady); assertEquals(0, f.secrets.reads)
        }
    }
    @Test fun secretReadRacingWipeCannotPublishReady() = runTest {
        val f = Fixture(); f.secrets.afterRead = { f.repository.current = f.repository.current!!.copy(phase = ProviderReservationPhase.DISABLED) }
        val facts = f.source.observe(f.binding).facts; assertTrue(facts.installBlocked); assertFalse(facts.providerReady)
    }
    @Test fun credentialFreeLocalRouteNeedsExplicitExactApprovalAndNoSecret() = runTest {
        val f = Fixture(); f.local = config.copy(baseUrl = "http://192.168.1.3:8080/v1", credentialReference = null)
        f.repository.current = null; f.repository.installs = emptyList()
        val binding = WearProviderBinding(f.local!!.provisioningBinding(config.id, false), WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL)
        assertFalse(f.source.observe(binding).facts.providerReady); f.approvedFree = true
        assertTrue(f.source.observe(binding).facts.providerReady); assertEquals(0, f.secrets.reads)
        assertFalse(f.source.observe(binding.copy(route = WearEndpointRoute.PUBLIC_HTTPS)).facts.providerReady)
    }
    @Test fun invalidEndpointsNeverReadCredentialsOrProbe() = runTest {
        for (url in listOf("http://provider.example/v1", "https://user:credential@provider.example/v1", "not-a-url", "https://provider.example/v1?key=secret")) {
            val f = Fixture(); f.local = config.copy(baseUrl = url)
            assertFalse(f.source.observe(f.binding).facts.providerReady); assertEquals(0, f.secrets.reads)
        }
    }
    @Test fun unapprovedBindingIsNotAnInstallationFailure() = runTest {
        val f = Fixture(); f.local = config.copy(credentialReference = null)
        f.repository.current = ProviderCredentialRevisionState(target, config.id.value); f.repository.installs = emptyList()
        val facts = f.source.observe(f.binding).facts; assertFalse(facts.providerReady); assertFalse(facts.installBlocked); assertFalse(facts.exactBindingApproved)
    }
    @Test fun temporarySecureStoreAvailabilityDoesNotMasqueradeAsCredentialChange() = runTest {
        val f = Fixture(); val available = f.source.observe(f.binding)
        f.secrets.unavailable = true; val unavailable = f.source.observe(f.binding)
        assertFalse(unavailable.facts.providerReady); assertEquals(available.generation, unavailable.generation)
        f.secrets.unavailable = false; assertEquals(available.generation, f.source.observe(f.binding).generation)
        f.repository.current = f.repository.current!!.copy(generation = ProviderLocalCounter.of(1))
        assertNotEquals(available.generation, f.source.observe(f.binding).generation)
    }
    @Test fun toolSupportFlagIsProbeCapabilityNotAnExtraReadinessBoolean() = runTest {
        val f = Fixture(); f.local = config.copy(toolCallingSupported = false)
        val binding = f.binding.copy(metadata = f.local!!.provisioningBinding(config.id, true))
        f.repository.current = f.repository.current!!.copy(activeBinding = binding.metadata, approvedBinding = binding.metadata)
        assertTrue(f.source.observe(binding).facts.providerReady)
        // The existing adapter then reports Unsupported and the probe stops; no prose/write fallback.
    }
}
