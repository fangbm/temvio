package dev.agenticscheduler.wear

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.wear.presentation.*
import dev.agenticscheduler.wear.capability.WearLocalSettings
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class WearCredentialSetupInstrumentedTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var presentation:WearCredentialSetupPresentation?=null
    private var real:RealWearCredentialFixture?=null
    private val screen=mutableStateOf<WearCredentialSetupPresentation?>(null)
    private val dark=mutableStateOf(true)
    private var backs=0
    @Before fun watch(){assertTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))}
    @After fun close():Unit=runBlocking {withContext(Dispatchers.Main.immediate){presentation?.close();scope.cancel()};real?.close()}
    private fun render(p:WearCredentialSetupPresentation){presentation=p;screen.value=p
        ui.setContent {WearProductTheme(dark.value){screen.value?.let {WearCredentialSetupScreen(it){backs++;it.close()}}}}}
    private fun safe(tag:String){
        ui.onNodeWithTag("wear-credential-setup").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val rect=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot();val c=context.resources.configuration
        val cx=c.screenWidthDp/2f;val cy=c.screenHeightDp/2f;val radius=minOf(cx,cy)
        for(x in listOf(rect.left.value,rect.right.value))for(y in listOf(rect.top.value,rect.bottom.value))
            assertTrue("$tag clipped by round edge: $rect",(x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius+2f)
    }
    private fun click(tag:String){safe(tag);ui.onNodeWithTag(tag).performClick()}
    private fun input(value:String){safe("credential-code");ui.onNodeWithTag("credential-code").performTextClearance();ui.onNodeWithTag("credential-code").performTextInput(value)}
    private fun waitState(predicate:(ProviderTargetState)->Boolean){ui.waitUntil(20000){presentation?.view?.value?.let{!it.busy&&predicate(it.state)}==true}}
    private fun noSecrets(vararg forbidden:String){val nodes=ui.onAllNodes(SemanticsMatcher("all nodes"){true},useUnmergedTree=true).fetchSemanticsNodes();val text=nodes.joinToString {it.config.toString()}
        listOf("SecretReference","ProviderCredentialEnvelope","bindingDigest","preparedReference","installIdentity","PRIVATE_BODY_NOT_FOR_UI").plus(forbidden).forEach{assertFalse(it,text.contains(it))}}
    @Test fun separateSendingCodeAndExplicitInstallAndAckRetry() {
        val comparison=CredentialSetupFixtures.comparison();val port=CredentialSetupFixtures.Port(ProviderTargetState.ComparisonReady(comparison))
        render(WearCredentialSetupPresentation(port,CredentialSetupFixtures.binding,scope));waitState{it is ProviderTargetState.ComparisonReady}
        ui.onNodeWithTag("wear-credential-setup").performScrollToNode(hasTestTag("credential-approve"));ui.onNodeWithTag("credential-approve").assertIsNotEnabled()
        input("1234");ui.onNodeWithTag("wear-credential-setup").performScrollToNode(hasTestTag("credential-approve"));ui.onNodeWithTag("credential-approve").assertIsNotEnabled();assertTrue(port.calls.isEmpty())
        input("87654321");click("credential-approve");waitState{it is ProviderTargetState.Blocked}
        assertSame(comparison,port.approved!!.first);assertEquals("87654321",port.approved!!.second);assertFalse("install" in port.calls)
        click("credential-retry");waitState{it is ProviderTargetState.ComparisonReady};assertEquals("",presentation!!.view.value.enteredCode)
        input("12345678");click("credential-approve");waitState{it is ProviderTargetState.UserApproved}
        assertFalse("install" in port.calls);assertEquals("12345678",port.approved!!.second)
        click("credential-install");waitState{it is ProviderTargetState.Installed};assertEquals(1,port.calls.count{it=="install"})
        click("credential-retry");waitState{it is ProviderTargetState.Installed && it.acknowledgement.status==ProviderTargetAckStatus.CONFIRMED}
        assertEquals(1,port.calls.count{it=="install"});noSecrets();click("credential-back");assertEquals(1,backs)
    }
    @Test fun sourceRequestUnconfirmedRetryAndFetchAreOnlyExplicit() {
        val port=CredentialSetupFixtures.Port(CredentialSetupFixtures.ready());val p=WearCredentialSetupPresentation(port,CredentialSetupFixtures.binding,scope)
        p.open();render(p);waitState{it is ProviderTargetState.Ready};assertEquals(listOf("resume"),port.calls)
        ui.onNodeWithTag("wear-credential-setup").performScrollToNode(hasTestTag("credential-request"));ui.onNodeWithTag("credential-request").assertIsNotEnabled()
        click("credential-source-0");click("credential-request");waitState{it is ProviderTargetState.Reserved};assertEquals(CredentialSetupFixtures.source,port.chosen)
        click("credential-retry");waitState{it is ProviderTargetState.Reserved && it.publication==ProviderTargetPublication.PUBLISHED}
        click("credential-fetch");waitState{it is ProviderTargetState.ComparisonReady}
        assertEquals(listOf("resume","request","retry","fetch"),port.calls);assertFalse("approve" in port.calls);assertFalse("install" in port.calls)
    }
    @Test fun themeRecompositionBackAndNewComparisonNeverProgressOrRetainCode() {
        val port=CredentialSetupFixtures.Port(ProviderTargetState.ComparisonReady(CredentialSetupFixtures.comparison()))
        render(WearCredentialSetupPresentation(port,CredentialSetupFixtures.binding,scope));waitState{it is ProviderTargetState.ComparisonReady};input("87654321")
        ui.runOnIdle{dark.value=false};ui.waitForIdle();assertEquals("87654321",presentation!!.view.value.enteredCode);assertTrue(port.calls.isEmpty())
        ui.runOnIdle{port.state.value=ProviderTargetState.ComparisonReady(CredentialSetupFixtures.comparison(2))};ui.waitUntil(10000){presentation!!.view.value.enteredCode.isEmpty()}
        click("credential-back");assertTrue(port.calls.isEmpty());assertEquals("",presentation!!.view.value.enteredCode)
    }
    @Test fun recoveryAndInstalledCopyHaveReachableSafeActionsWithoutSecretFields() {
        val port=CredentialSetupFixtures.Port(ProviderTargetState.RecoveryRequired(CredentialSetupFixtures.flow))
        render(WearCredentialSetupPresentation(port,CredentialSetupFixtures.binding,scope));waitState{it is ProviderTargetState.RecoveryRequired}
        click("credential-recover");waitState{it is ProviderTargetState.Ready};assertEquals(listOf("recover"),port.calls)
        ui.runOnIdle{port.state.value=ProviderTargetState.Installed(CredentialSetupFixtures.flow,ProviderTargetAcknowledgement(ProviderTargetAckStatus.BLOCKED,
            ProviderTargetFailure.Transport(ProviderTargetTransportFailure.AUTHORIZATION)))}
        waitState{it is ProviderTargetState.Installed};ui.onNodeWithTag("wear-credential-setup").performScrollToNode(hasText("Credential installed"));ui.onNodeWithText("Credential installed").assertIsDisplayed()
        ui.onNodeWithText("Installation failed",substring=true).assertDoesNotExist();noSecrets();click("credential-back")
    }
    private fun realStart(namespace:String):RealWearCredentialFixture {
        val fixture=RealWearCredentialFixture(context,namespace);real=fixture;runBlocking{fixture.initialize()}
        val p=WearCredentialSetupPresentation(fixture.coordinator(),fixture.binding,scope){
            val observation=fixture.providerObservation();assertNotNull(observation.config?.credentialReference);assertTrue(observation.facts.matchingSecretAvailable)
        };p.open();render(p);waitState{it is ProviderTargetState.Ready};return fixture
    }
    private fun approveThroughRealUi(f:RealWearCredentialFixture){
        val state=presentation!!.view.value.state as ProviderTargetState.Ready
        click("credential-source-${state.provisioners.indexOf(f.source)}");click("credential-request");waitState{it is ProviderTargetState.Reserved}
        assertEquals(1,f.publishes);assertEquals(0,f.fetches);assertEquals(0,f.imports);click("credential-fetch");waitState{it is ProviderTargetState.ComparisonReady}
        val independent=ProviderProvisioningSas.calculate(f.source,f.envelope,f.binding)
        input(if(independent=="00000000")"00000001" else "00000000");click("credential-approve");waitState{it is ProviderTargetState.Blocked}
        assertEquals(0,f.imports);assertEquals(0,f.acks);click("credential-retry");waitState{it is ProviderTargetState.ComparisonReady}
        input(independent);click("credential-approve");waitState{it is ProviderTargetState.UserApproved};assertEquals(0,f.imports);assertEquals(0,f.acks)
    }
    private fun recreate(f:RealWearCredentialFixture){
        ui.runOnIdle{presentation!!.close();screen.value=null};f.reopen()
        val p=WearCredentialSetupPresentation(f.coordinator(),f.binding,scope){assertTrue(f.providerObservation().facts.matchingSecretAvailable)}
        ui.runOnIdle{presentation=p;screen.value=p;p.open()}
    }
    @Test fun realUserApprovedRoomRecreationStillNeedsExplicitInstall() {
        val f=realStart("approved-restart");approveThroughRealUi(f);recreate(f);waitState{it is ProviderTargetState.UserApproved}
        assertEquals(0,f.imports);assertEquals(0,f.acks);click("credential-install");waitState{it is ProviderTargetState.Installed}
        assertEquals(1,f.imports);assertEquals(1,f.acks);assertFalse(WearLocalSettings(f.context).userEnabledAiEntry.value);noSecrets(f.secret)
    }
    @Test fun realActiveRoomRecreationAndFailedAckRetryNeverReinstall() {
        val f=realStart("active-restart");approveThroughRealUi(f);f.ackFails=true;click("credential-install");waitState{it is ProviderTargetState.Installed}
        assertEquals(1,f.imports);assertEquals(1,f.acks);val ref=runBlocking{f.local.providerConfig(f.config)!!.credentialReference!!}
        recreate(f);waitState{it is ProviderTargetState.Installed};assertEquals(1,f.imports);assertEquals(1,f.acks)
        noSecrets(f.secret,ref.value);f.ackFails=false;click("credential-retry");waitState{it is ProviderTargetState.Installed && it.acknowledgement.status==ProviderTargetAckStatus.CONFIRMED}
        assertEquals(1,f.imports);assertEquals(2,f.acks);assertEquals(ref,runBlocking{f.local.providerConfig(f.config)!!.credentialReference})
    }
}
