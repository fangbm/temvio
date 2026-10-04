package dev.agenticscheduler.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.application.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore protection, including store recreation; no generic import destination. */
@RunWith(AndroidJUnit4::class)
class ProviderCredentialSlotInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun providerPreparedSlotIsPurposeScopedOneInstallAndSurvivesRecreation() = runBlocking {
        val first = AndroidKeystoreSecureStore(context)
        val material = object : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial {
            override fun copyRawKeyBytesForPairing() = ByteArray(32) { it.toByte() }
        }
        val amk = first.generateAccountMasterKey()
        val content = first.generateContentKey()
        val hpke = first.generatePairingDeviceKey()
        val generic = first.importSecret(material)
        val device = first.generate()
        val protected = listOf(amk, content.reference, hpke.privateKeyReference, generic, device.reference)
        val slot = first.prepareProviderSlot()
        try {
            val reopened = AndroidKeystoreSecureStore(context)
            for (ref in protected) assertNull(reopened.restoreProviderSlot(ref, slot.installIdentity))
            assertNull(reopened.readProviderSecret(slot.reference))
            val restored = checkNotNull(reopened.restoreProviderSlot(slot.reference, slot.installIdentity))
            reopened.importPreparedProviderSecret(restored, "public-test-provider-credential".encodeToByteArray())
            assertNull(reopened.restoreProviderSlot(slot.reference, "wrong-owner"))
            var reusedRejected = false
            try { reopened.importPreparedProviderSecret(restored, "overwrite".encodeToByteArray()) }
            catch (_: IllegalStateException) { reusedRejected = true }
            assertTrue(reusedRejected)
            val third = AndroidKeystoreSecureStore(context)
            assertEquals("public-test-provider-credential", checkNotNull(third.readProviderSecret(slot.reference)).decodeToString())
            assertEquals(32, checkNotNull(third.exportAccountMasterKeyForPairing(amk)).copyRawKeyBytesForPairing().size)
            assertNotNull(third.contentAead(content.reference))
            assertNotNull(third.privateKey(hpke.privateKeyReference))
            assertNotNull(third.readSecret(generic))
            assertNotNull(third.load(device.reference))
            val cleanup = checkNotNull(third.restoreProviderSlot(slot.reference, slot.installIdentity))
            third.deleteProviderSlot(cleanup)
            assertNull(AndroidKeystoreSecureStore(context).readProviderSecret(slot.reference))
            protected.forEach { assertNull(third.restoreProviderSlot(it, slot.installIdentity)) }
        } finally {
            first.restoreProviderSlot(slot.reference, slot.installIdentity)?.let { first.deleteProviderSlot(it) }
            protected.forEach { first.delete(it) }
        }
    }
    @Test fun preparedBeforeImportCanBeRecoveredAndCleanedWithoutTouchingActiveSlot() = runBlocking {
        val first = AndroidKeystoreSecureStore(context)
        val active = first.prepareProviderSlot()
        first.importPreparedProviderSecret(active, "existing-credential".encodeToByteArray())
        val abandoned = first.prepareProviderSlot()
        try {
            val reopened = AndroidKeystoreSecureStore(context)
            reopened.deleteProviderSlot(checkNotNull(reopened.restoreProviderSlot(abandoned.reference, abandoned.installIdentity)))
            assertNull(reopened.restoreProviderSlot(abandoned.reference, abandoned.installIdentity))
            assertEquals("existing-credential", checkNotNull(reopened.readProviderSecret(active.reference)).decodeToString())
        } finally {
            first.restoreProviderSlot(abandoned.reference, abandoned.installIdentity)?.let { first.deleteProviderSlot(it) }
            first.restoreProviderSlot(active.reference, active.installIdentity)?.let { first.deleteProviderSlot(it) }
        }
    }
}
