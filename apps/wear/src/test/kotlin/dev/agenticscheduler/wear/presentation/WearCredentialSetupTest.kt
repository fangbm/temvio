package dev.agenticscheduler.wear.presentation

import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WearCredentialSetupTest {
    private val target = DeviceId("Watch:opaque-A_123456")
    private val sender = DeviceId("Source:opaque-B_ABCDEF")
    private val config = "01900000-0000-7000-8000-000000000801"
    private val binding = WearProviderBindingMetadataV1(providerConfigId=config,baseUrl="https://synthetic.invalid/v1",model="Synthetic credential profile",
        maxContextUnits=8192,reservedOutputUnits=2048,streamingSupported=false,toolCallingSupported=true,credentialRequired=true)
    private val flow = ProviderTargetFlow(target, config, 1, sender)
    // Only test fixtures construct internal display handles. Production uses the exact received instance.
    private fun comparison(revision: Long = 1) = ProviderTargetComparison::class.java
        .getDeclaredConstructor(ProviderTargetFlow::class.java,String::class.java,String::class.java)
        .newInstance(flow.copy(revision=revision),binding.model,"12345678")
    private inner class Port(initial: ProviderTargetState) : ProviderCredentialTarget {
        override val state = MutableStateFlow(initial)
        val intents=mutableListOf<String>();var source: DeviceId?=null
        var approved: Pair<ProviderTargetComparison,String>?=null
        var gate: CompletableDeferred<Unit>?=null
        override suspend fun resume(config:String){intents+="resume"}
        override suspend fun reserveAndPublish(binding:WearProviderBindingMetadataV1,provisioner:DeviceId){intents+="request";source=provisioner;gate?.await()}
        override suspend fun fetchDelivery(){intents+="fetch"}
        override suspend fun approveComparison(displayed:ProviderTargetComparison,independentlyComparedCode:String){intents+="approve";approved=displayed to independentlyComparedCode}
        override suspend fun installApproved(){intents+="install"}
        override suspend fun retry(){intents+="retry"}
        override suspend fun recover(){intents+="recover"}
    }
    private fun ready()=ProviderTargetState.Ready(config,binding.model,listOf(target,sender))
    @Test fun `every target state has human copy and only its reviewed actions`() {
        val states=listOf(ProviderTargetState.NotStarted,ProviderTargetState.NotEligible(ProviderTargetEligibility.NO_ACTIVE_TARGET),ready(),
            ProviderTargetState.Reserved(flow,ProviderTargetPublication.UNCONFIRMED,false),ProviderTargetState.Reserved(flow,ProviderTargetPublication.PUBLISHED,false),
            ProviderTargetState.Reserved(flow,ProviderTargetPublication.PUBLISHED,true),ProviderTargetState.ComparisonReady(comparison()),ProviderTargetState.UserApproved(flow),
            ProviderTargetState.Installing(flow),ProviderTargetState.Installed(flow,ProviderTargetAcknowledgement(ProviderTargetAckStatus.CONFIRMED)),
            ProviderTargetState.Expired(flow),ProviderTargetState.RetryableFailure(ProviderTargetWork.FETCH,flow,ProviderTargetFailure.Transport(ProviderTargetTransportFailure.NETWORK)),
            ProviderTargetState.Blocked(ProviderTargetWork.APPROVE,flow,ProviderTargetFailure.Protocol(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)),ProviderTargetState.RecoveryRequired(flow))
        val actions=listOf(emptySet(),emptySet(),setOf(CredentialSetupAction.REQUEST),setOf(CredentialSetupAction.RETRY),setOf(CredentialSetupAction.FETCH),
            setOf(CredentialSetupAction.FETCH),setOf(CredentialSetupAction.APPROVE),setOf(CredentialSetupAction.INSTALL),emptySet(),emptySet(),setOf(CredentialSetupAction.REQUEST),
            setOf(CredentialSetupAction.RETRY),setOf(CredentialSetupAction.RETRY),setOf(CredentialSetupAction.RECOVER))
        states.zip(actions).forEach { (state,expected)->val c=credentialSetupCopy(state);assertTrue(c.title.isNotBlank());assertTrue(c.detail.isNotBlank());assertEquals(expected,c.actions) }
    }
    @Test fun `all failure identities map without raw enums or private fields`() {
        val failures=ProviderCredentialRejection.entries.map{ProviderTargetFailure.Protocol(it)}+
            ProviderTargetTransportFailure.entries.map{ProviderTargetFailure.Transport(it)}+
            ProviderTargetEligibility.entries.map{ProviderTargetFailure.Eligibility(it)}+ProviderTargetFailure.Internal
        failures.forEach { assertFalse(credentialFailureCopy(it).contains(Regex("[A-Z]{2,}_[A-Z_]+"))) }
    }
    @Test fun `all ACK statuses retain installed truth separately`() {
        ProviderTargetAckStatus.entries.forEach { ack -> val c=credentialSetupCopy(ProviderTargetState.Installed(flow,ProviderTargetAcknowledgement(ack)))
            assertEquals("Credential installed",c.title);assertFalse(c.detail.contains("Installation failed"));assertFalse(CredentialSetupAction.INSTALL in c.actions)
        }
    }
    @Test fun `opening setup only resumes and no source is chosen by ordering or Watch identity`()=runTest {
        val port=Port(ready());WearCredentialSetupPresentation(port,binding,this).use { p ->p.open();advanceUntilIdle()
            assertEquals(listOf("resume"),port.intents);assertNull(p.view.value.selectedSource)
            p.act(CredentialSetupAction.REQUEST);advanceUntilIdle();assertEquals(listOf("resume"),port.intents)
            p.chooseSource(target);p.act(CredentialSetupAction.REQUEST);advanceUntilIdle();assertEquals(target,port.source)
        }
    }
    @Test fun `explicit Request preserves exact chosen opaque source and delegates once`()=runTest {
        val port=Port(ready());WearCredentialSetupPresentation(port,binding,this).use { p -> advanceUntilIdle();p.chooseSource(sender)
            port.gate=CompletableDeferred();p.act(CredentialSetupAction.REQUEST);p.act(CredentialSetupAction.REQUEST);runCurrent()
            assertEquals(listOf("request"),port.intents);assertEquals(sender,port.source);port.gate!!.complete(Unit);advanceUntilIdle()
        }
    }
    @Test fun `Published check and unconfirmed retry use different exact intents`()=runTest {
        val port=Port(ProviderTargetState.Reserved(flow,ProviderTargetPublication.PUBLISHED,false))
        WearCredentialSetupPresentation(port,binding,this).use { p ->advanceUntilIdle();p.act(CredentialSetupAction.FETCH);advanceUntilIdle()
            port.state.value=ProviderTargetState.Reserved(flow,ProviderTargetPublication.UNCONFIRMED,false);advanceUntilIdle();p.act(CredentialSetupAction.RETRY);advanceUntilIdle()
            assertEquals(listOf("fetch","retry"),port.intents)
        }
    }
    @Test fun `independent input never copies Watch code and passes exact received comparison`()=runTest {
        val c=comparison();val port=Port(ProviderTargetState.ComparisonReady(c))
        WearCredentialSetupPresentation(port,binding,this).use { p ->advanceUntilIdle();assertEquals("",p.view.value.enteredCode);assertFalse(p.view.value.canApprove)
            listOf("1234","1234567x","１２３４５６７８","").forEach { p.enterCode(it);p.act(CredentialSetupAction.APPROVE);assertFalse(p.view.value.canApprove) }
            advanceUntilIdle();assertTrue(port.intents.isEmpty())
            p.enterCode("87654321");assertTrue(p.view.value.canApprove);p.act(CredentialSetupAction.APPROVE);advanceUntilIdle()
            assertSame(c,port.approved!!.first);assertEquals("87654321",port.approved!!.second) // Would fail if code were auto-copied.
            p.enterCode("12345678");p.act(CredentialSetupAction.APPROVE);advanceUntilIdle();assertEquals("12345678",port.approved!!.second)
            assertFalse("install" in port.intents)
        }
    }
    @Test fun `comparison replacement clears draft including stale observer race`()=runTest {
        val port=Port(ProviderTargetState.ComparisonReady(comparison()))
        WearCredentialSetupPresentation(port,binding,this).use { p ->advanceUntilIdle();p.enterCode("12345678")
            port.state.value=ProviderTargetState.ComparisonReady(comparison(2));p.act(CredentialSetupAction.APPROVE)
            advanceUntilIdle();assertEquals("",p.view.value.enteredCode);assertTrue(port.intents.isEmpty())
            p.enterCode("12345678");port.state.value=ProviderTargetState.UserApproved(flow);advanceUntilIdle();assertEquals("",p.view.value.enteredCode)
        }
    }
    @Test fun `approved requires separate install and Installed retry invokes only ACK delegation`()=runTest {
        val port=Port(ProviderTargetState.UserApproved(flow));var refreshed=0
        WearCredentialSetupPresentation(port,binding,this,{refreshed++}).use { p ->advanceUntilIdle();assertTrue(port.intents.isEmpty())
            p.act(CredentialSetupAction.INSTALL);advanceUntilIdle();assertEquals(listOf("install"),port.intents)
            port.state.value=ProviderTargetState.Installed(flow,ProviderTargetAcknowledgement(ProviderTargetAckStatus.PENDING));advanceUntilIdle()
            p.act(CredentialSetupAction.INSTALL);p.act(CredentialSetupAction.RETRY);advanceUntilIdle();assertEquals(listOf("install","retry"),port.intents);assertEquals(1,refreshed)
        }
    }
    @Test fun `recovery and explicit expired reuse never automatically request install or approve`()=runTest {
        val port=Port(ProviderTargetState.RecoveryRequired(flow));WearCredentialSetupPresentation(port,binding,this).use { p ->advanceUntilIdle();p.act(CredentialSetupAction.RECOVER);advanceUntilIdle()
            port.state.value=ProviderTargetState.Expired(flow);advanceUntilIdle();assertEquals(listOf("recover"),port.intents)
            p.act(CredentialSetupAction.REQUEST);advanceUntilIdle();assertEquals(sender,port.source);assertEquals(listOf("recover","request"),port.intents)
        }
    }
    @Test fun `closing cancels presentation work without protocol rollback or code persistence`()=runTest {
        val port=Port(ProviderTargetState.ComparisonReady(comparison()));val p=WearCredentialSetupPresentation(port,binding,this)
        advanceUntilIdle();p.enterCode("87654321");assertFalse(p.view.value.toString().contains("87654321"));p.close();advanceUntilIdle();assertTrue(port.intents.isEmpty());assertEquals("",p.view.value.enteredCode)
    }
}
