package dev.agenticscheduler.wear

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.MaterialTheme
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.*
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.id.*
import dev.agenticscheduler.agent.runtime.AgentClock
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.*
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.id.TaskId
import kotlin.time.Duration.Companion.ZERO
import dev.agenticscheduler.wear.agent.*
import dev.agenticscheduler.wear.capability.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Core acceptance: actual Watch platform, Android Ktor engine, file Room, shared AgentRunService and Tools. */
@RunWith(AndroidJUnit4::class)
class WearAgentRuntimeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: Context
    private lateinit var database: AgenticSchedulerDatabase
    private lateinit var d8: RoomD8RuntimeComposition
    private lateinit var agent: WearAgentRuntimeComposition
    private lateinit var fixture: StructuredFixture
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    private var permissionRequests = 0
    private val renderedAgent = mutableStateOf<WearAgentRuntimeComposition?>(null)
    private val renderedConfig = mutableStateOf<ProviderConfig?>(null)
    private var rendered = false
    private val cleanupReferences = mutableListOf<SecretReference>()
    private var nextIdEpoch = 1_800_000_000_000L
    private var nextEventEpoch = 1_800_000_000_000L
    private val testIds = RfcUuidV7Generator(EpochMillisecondsClock { nextIdEpoch++ }, RandomBytes { size -> ByteArray(size) })
    private val testClock = AgentClock { nextEventEpoch++ }
    private val configId = ProviderConfigId("01900000-0000-7000-8000-000000000321")

    @Before fun start() = runBlocking {
        assertTrue("Core E2E must run on a real Wear AVD, never phone/fake platform", base.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
        val namespace = "d90303_${testName.methodName}"
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = super.getDatabasePath("${namespace}_$name")
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${namespace}_$name", mode)
        }
        context.deleteDatabase("${namespace}_$AgenticSchedulerDatabaseFileName")
        context.getSharedPreferences("wear_ai_entry_v1", Context.MODE_PRIVATE).edit().clear().commit()
        fixture = StructuredFixture()
        open()
        val config = ProviderConfig(configId, "http://127.0.0.1:${fixture.port}/v1", "deterministic-structured-fixture", 65536, 8192, false, true, null)
        agent.state.saveProviderConfig(config)
        renderedConfig.value = config
        agent.approveCredentialFreeFromUserAction(config, WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL)
        agent.readiness.settings.setAiEntryFromExplicitUserAction(true)
        withContext(Dispatchers.Main.immediate) { agent.readiness.start() }
        agent.foregroundChanged(true)
        awaitReady()
        agent.session.restoreRecentThread()
        render()
    }
    private suspend fun open() {
        database = openAndroidDatabase(context)
        d8 = RoomD8RuntimeComposition(database, AndroidKeystoreSecureStore(context), TinkPairingHpke(),
            testIds, MutationWallClock { nextEventEpoch++ })
        assertEquals(ActiveSyncRuntimeCreation.NoEnrollment, d8.activateWithoutConfiguration())
        agent = WearAgentRuntimeComposition.createActive(context, database, scope, d8, { callback -> permissionRequests++; callback(false) }, "en-US", testIds, testClock)
        closed = false
    }
    private suspend fun awaitReady() = withTimeout(20000) {
        agent.readiness.readiness.first { it.runtimeState == WearProviderRuntimeState.READY }
        val snapshot = requireNotNull(agent.readiness.requestSnapshot())
        assertEquals(dev.agenticscheduler.agent.provider.ProviderProbeResult.Supported, agent.readiness.structuredCapability(snapshot))
    }
    private fun render() {
        renderedAgent.value = agent
        if (rendered) return
        rendered = true
        compose.setContent {
            val current = requireNotNull(renderedAgent.value)
            val state by current.session.ui.collectAsState()
            val readiness by current.readiness.readiness.collectAsState()
            MaterialTheme { WearAgentScreen(state, readiness, renderedConfig.value, current.session::editDraft,
                { scope.launch { current.session.submitFromUserAction() } }, { scope.launch { current.session.newConversationFromUserAction() } },
                { call, preview, approve -> scope.launch { current.session.confirmFromUserAction(call, preview, approve) } },
                current.readiness.settings::setAiEntryFromExplicitUserAction, { scope.launch { current.readiness.refresh(true) } },
                { current.readiness.capability.useSpeechFromExplicitUserAction(current.session::speechCandidate) }, current.readiness.capability::cancel,
                current.readiness.capability::selectLanguageFromUserAction) }
        }
    }
    private fun send(text: String) {
        compose.onNodeWithTag("agent-input").performScrollTo().performTextReplacement(text)
        compose.onNodeWithTag("agent-send").performScrollTo().performClick()
        compose.waitUntil(20000) { !agent.session.ui.value.busy && agent.session.ui.value.messages.any { it.role == AgentMessageRole.USER && it.content == text } }
    }
    private fun confirm(approved: Boolean) {
        compose.onNodeWithTag(if (approved) "agent-confirm" else "agent-deny").performScrollTo().performClick()
        compose.waitUntil(20000) { !agent.session.ui.value.busy && agent.session.ui.value.pending == null }
    }
    private suspend fun reopen() {
        agent.close(); d8.deactivate(); database.close(); closed = true
        open(); agent.refreshSelectedBinding()
        withContext(Dispatchers.Main.immediate) { agent.readiness.start() }
        agent.foregroundChanged(true); awaitReady(); agent.session.restoreRecentThread(); render()
    }
    @After fun stop() = runBlocking {
        if (::agent.isInitialized && !closed) { agent.close(); d8.deactivate(); database.close() }
        scope.cancel()
        val secrets = AndroidKeystoreSecureStore(context)
        cleanupReferences.forEach { secrets.delete(it) }
        if (::fixture.isInitialized) fixture.close()
    }

    @Test fun readCommandUsesRealProviderToolAndRoomWithoutBusinessMutation() = runBlocking {
        send("read tasks")
        val thread = requireNotNull(agent.session.ui.value.threadId)
        assertEquals(AgentToolResultStatus.SUCCESS, agent.state.toolResults(thread).single().status)
        assertTrue(agent.state.messages(thread).any { it.role == AgentMessageRole.ASSISTANT && it.content == "Structured fixture complete" })
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        assertTrue(RoomTaskRepository(database).observeTasks().first().isEmpty())
        val transport = RoomAgentSyncTransportPersistence(database); val space = SyncSpaceId("no-consent-watch")
        assertFalse(transport.conversationConsent(space)); assertTrue(transport.outboundRecords(space).isEmpty())
        val before = agent.state.messages(thread)
        reopen(); assertEquals(before, agent.state.messages(thread)); assertEquals(thread, agent.session.ui.value.threadId)
        assertEquals(0, permissionRequests)
        assertFalse(fixture.bodies.any { it.contains("SecretRef") || it.contains("credential_secret_ref") || it.contains("bindingDigest") })
    }

    @Test fun unsupportedStructuredProbeAllowsExplicitChatWithZeroToolSchemasOrMutations() = runBlocking {
        fixture.probeSupported = false
        agent.readiness.refresh(true)
        withTimeout(20000) { agent.readiness.readiness.first { it.providerProbeFailure == WearProbeFailure.UNSUPPORTED_TOOLS } }
        val snapshot = requireNotNull(agent.readiness.requestSnapshot())
        assertEquals(dev.agenticscheduler.agent.provider.ProviderProbeResult.Unsupported, agent.readiness.structuredCapability(snapshot))
        assertEquals(WearProviderRuntimeState.READY, agent.readiness.readiness.value.runtimeState)
        compose.onNodeWithTag("chat-only").performScrollTo().assertTextContains("Chat-only · structured Tools unavailable")
        val probes = fixture.probeRequests
        val firstBody = fixture.bodies.size
        for (prompt in listOf("normal chat", "create a task", "update the task")) {
            send(prompt) // Actual production Send remains usable in chat-only mode.
            val thread = requireNotNull(agent.session.ui.value.threadId)
            assertEquals(StructuredFixture.CHAT_ONLY_REPLY, agent.state.messages(thread).last().content)
            assertEquals(AgentMessageRole.ASSISTANT, agent.state.messages(thread).last().role)
            assertTrue(agent.state.toolCalls(thread).isEmpty())
            assertTrue(agent.state.toolResults(thread).isEmpty())
            assertTrue(agent.state.actions(thread).isEmpty())
            assertNull(agent.session.ui.value.pending)
            assertEquals(WearAgentUiPhase.IDLE, agent.session.ui.value.phase)
        }
        val commands = fixture.bodies.drop(firstBody).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(3, commands.size)
        commands.forEach { assertTrue(it["tools"]?.jsonArray.orEmpty().isEmpty()) }
        assertEquals(probes, fixture.probeRequests) // Explicit commands reuse terminal Unsupported proof.
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        assertTrue(RoomTaskRepository(database).observeTasks().first().isEmpty())
    }

    @Test fun authenticationInvalidConfigAndNetworkLossStillBlockExplicitCommands() = runBlocking {
        for ((status, failure) in listOf(401 to WearProbeFailure.AUTHENTICATION, 400 to WearProbeFailure.INVALID_CONFIG)) {
            fixture.probeStatus = status
            agent.readiness.refresh(true)
            withTimeout(20000) { agent.readiness.readiness.first { it.providerProbeFailure == failure } }
            assertEquals(WearProviderRuntimeState.PROVIDER_UNAVAILABLE, agent.readiness.readiness.value.runtimeState)
            assertNull(agent.readiness.requestSnapshot())
            agent.session.editDraft("create task despite HTTP $status")
            compose.onNodeWithTag("agent-send").performScrollTo().assertIsNotEnabled()
            val requests = fixture.commandRequests
            agent.session.submitFromUserAction()
            assertEquals(requests, fixture.commandRequests)
        }
        agent.readiness.network.close()
        agent.readiness.refresh()
        assertEquals(WearProviderRuntimeState.OFFLINE, agent.readiness.readiness.value.runtimeState)
        assertNull(agent.readiness.requestSnapshot())
        agent.session.editDraft("create task despite network loss")
        compose.onNodeWithTag("agent-send").performScrollTo().assertIsNotEnabled()
        val requests = fixture.commandRequests
        agent.session.submitFromUserAction()
        assertEquals(requests, fixture.commandRequests)
        val thread = requireNotNull(agent.session.ui.value.threadId)
        assertTrue(agent.state.messages(thread).isEmpty())
        assertTrue(agent.state.toolCalls(thread).isEmpty())
        assertTrue(agent.state.actions(thread).isEmpty())
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
    }

    @Test fun watchConfirmAfterFileRestartCommitsExactlyOnceWithLinkedAudit() = runBlocking {
        // Persisted ALLOW_DIRECT cannot relax the Watch write ceiling.
        agent.state.savePermissionPolicy(AgentPermissionPolicy.default().withMode(AgentToolCapability.LOW_RISK_CREATE, AgentPermissionMode.ALLOW_DIRECT))
        send("create task")
        val pending = requireNotNull(agent.session.ui.value.pending)
        assertTrue(RoomTaskRepository(database).observeTasks().first().isEmpty())
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        reopen()
        assertEquals(pending, agent.session.ui.value.pending)
        confirm(true)
        assertLinkedSuccess()
        val thread = requireNotNull(agent.session.ui.value.threadId)
        agent.session.confirmFromUserAction(pending.id, requireNotNull(pending.previewJson), true)
        assertEquals(1, RoomTaskRepository(database).observeTasks().first().size)
        assertEquals(1, RoomMutationJournalRepository(database).timeline().size)
        reopen(); assertNull(agent.session.ui.value.pending); assertEquals(1, agent.state.toolResults(thread).size)
    }

    @Test fun watchDenyAfterFileRestartPreservesDenialAndZeroMutation() = runBlocking {
        send("create task"); val pending = requireNotNull(agent.session.ui.value.pending)
        reopen(); assertEquals(pending, agent.session.ui.value.pending); confirm(false)
        val thread = requireNotNull(agent.session.ui.value.threadId)
        assertEquals(AgentToolResultStatus.PERMISSION_DENIED, agent.state.toolResults(thread).single().status)
        assertTrue(RoomTaskRepository(database).observeTasks().first().isEmpty()); assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        reopen(); assertEquals(AgentToolResultStatus.PERMISSION_DENIED, agent.state.toolResults(thread).single().status)
    }

    @Test fun localConfirmationCommitsWhenProviderBindingIsRemovedWithoutReplay() = runBlocking {
        send("create task"); val pending = requireNotNull(agent.session.ui.value.pending)
        agent.state.selectProviderConfig(null); agent.refreshSelectedBinding()
        val before = fixture.commandRequests
        confirm(true)
        assertLinkedSuccess()
        assertEquals(before, fixture.commandRequests)
        assertEquals(WearAgentUiPhase.PROVIDER_UNAVAILABLE, agent.session.ui.value.phase)
        assertEquals(AgentToolResultStatus.SUCCESS, agent.session.ui.value.results.single().status)
        agent.session.confirmFromUserAction(pending.id, requireNotNull(pending.previewJson), true)
        assertEquals(1, RoomMutationJournalRepository(database).timeline().size)
    }

    @Test fun foregroundLossBlocksQueuedConfirmAndSendUntilAnotherExplicitAction() = runBlocking {
        send("create task"); val pending = requireNotNull(agent.session.ui.value.pending)
        val before = fixture.commandRequests
        withContext(Dispatchers.Main.immediate) { agent.stopForegroundFromLifecycle() }
        agent.session.confirmFromUserAction(pending.id, requireNotNull(pending.previewJson), true)
        agent.session.editDraft("another create task"); agent.session.submitFromUserAction()
        assertEquals("FOREGROUND_REQUIRED", agent.session.ui.value.redactedCode)
        assertEquals(pending, agent.session.ui.value.pending)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        assertEquals(before, fixture.commandRequests)
        agent.foregroundChanged(true); delay(200)
        assertEquals(before, fixture.commandRequests)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        confirm(false)
    }

    @Test fun deniedLocalPolicyDoesNotBecomeConfirmationOrWrite() = runBlocking {
        agent.state.savePermissionPolicy(AgentPermissionPolicy.default().withMode(AgentToolCapability.LOW_RISK_CREATE, AgentPermissionMode.DENY))
        send("create task")
        assertNull(agent.session.ui.value.pending)
        assertEquals(AgentToolResultStatus.PERMISSION_DENIED, agent.session.ui.value.results.single().status)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
    }

    @Test fun speechCandidateIsDraftOnlyAndPendingBlocksAnotherCommand() = runBlocking {
        val before = fixture.commandRequests
        agent.session.speechCandidate(SpeechCandidateResult.TextCandidate("create task"))
        assertEquals("create task", agent.session.ui.value.draft); assertEquals(before, fixture.commandRequests)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        send("create task"); val pending = requireNotNull(agent.session.ui.value.pending)
        val commandCount = fixture.commandRequests
        agent.session.editDraft("second create"); agent.session.submitFromUserAction()
        assertEquals(pending, agent.session.ui.value.pending); assertEquals(commandCount, fixture.commandRequests)
        assertEquals(1, agent.session.ui.value.messages.count { it.role == AgentMessageRole.USER })
    }

    @Test fun displayedPreviewMismatchFailsClosedWithoutMutation() = runBlocking {
        send("create task"); val pending = requireNotNull(agent.session.ui.value.pending)
        agent.session.confirmFromUserAction(pending.id, requireNotNull(pending.previewJson) + " ", true)
        assertEquals(pending, agent.session.ui.value.pending)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        assertTrue(RoomTaskRepository(database).observeTasks().first().isEmpty())
    }

    @Test fun changedSourceFactIsTypedStaleWithoutBlindApply() = runBlocking {
        val id = TaskId("01900000-0000-7000-8000-000000000324")
        val tasks = RoomTaskRepository(database)
        val before = Task(id, "Before", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, ZERO, null), null)
        tasks.upsertTask(before)
        fixture.updateTaskId = id.value
        send("update task"); assertNotNull(agent.session.ui.value.pending)
        tasks.upsertTask(before.copy(title = "Authoritative changed source"))
        confirm(true)
        assertEquals(AgentToolResultStatus.STALE, agent.session.ui.value.results.single().status)
        assertEquals("Authoritative changed source", tasks.getTask(id)?.title)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
    }

    @Test fun nonLoopbackCredentialFreeHttpReadUsesRealEngineAndVisibleWarning() = runBlocking {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val address = requireNotNull(manager.getLinkProperties(requireNotNull(manager.activeNetwork))).linkAddresses
            .map { it.address }.first { it is java.net.Inet4Address && !it.isLoopbackAddress }.hostAddress
        val config = requireNotNull(agent.state.providerConfig(configId)).copy(baseUrl = "http://$address:${fixture.port}/v1")
        agent.state.saveProviderConfig(config)
        agent.approveCredentialFreeFromUserAction(config, WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL)
        awaitReady(); renderedConfig.value = config
        compose.onNodeWithTag("http-warning").performScrollTo().assertTextContains("请求内容可能通过未加密 HTTP 传输")
        send("read tasks")
        assertEquals(AgentToolResultStatus.SUCCESS, agent.session.ui.value.results.single().status)
        assertTrue(fixture.authorizationHeaders.all { it == null })
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
    }

    @Test fun runtimeRedirectDoesNotForwardConversationToAnotherEndpoint() = runBlocking {
        val redirected = StructuredFixture()
        try {
            fixture.redirectCommands = "http://127.0.0.1:${redirected.port}/v1/chat/completions"
            send("read tasks")
            assertEquals(WearAgentUiPhase.PROVIDER_UNAVAILABLE, agent.session.ui.value.phase)
            assertEquals("HTTP_302", agent.session.ui.value.redactedCode)
            assertTrue(redirected.bodies.isEmpty())
            assertTrue(agent.session.ui.value.results.isEmpty())
            assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        } finally { redirected.close() }
    }

    @Test fun doubleSendAndSendWhileRunningProduceOnlyOneTurnAndProposal() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        fixture.commandBarrier = release
        agent.session.editDraft("create task")
        val first = scope.launch { agent.session.submitFromUserAction() }
        try {
            compose.waitUntil(10000) { fixture.commandRequests > 0 && agent.session.ui.value.busy }
            agent.session.submitFromUserAction()
            agent.session.editDraft("other create task"); agent.session.submitFromUserAction()
            assertEquals(1, fixture.commandRequests)
        } finally { release.countDown() }
        withTimeout(15000) { first.join() }
        assertEquals(1, agent.session.ui.value.messages.count { it.role == AgentMessageRole.USER })
        assertNotNull(agent.session.ui.value.pending)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
    }

    @Test fun entryOffAndStaleBindingDenyBeforeAnyCommandSend() = runBlocking {
        val old = requireNotNull(agent.readiness.requestSnapshot())
        val before = fixture.commandRequests
        agent.readiness.settings.setAiEntryFromExplicitUserAction(false)
        agent.session.editDraft("read tasks"); agent.session.submitFromUserAction()
        assertEquals(before, fixture.commandRequests)
        agent.readiness.settings.setAiEntryFromExplicitUserAction(true)
        agent.state.saveProviderConfig(old.config.copy(model = "new-exact-binding"))
        assertFalse(agent.readiness.authorizes(old, old.config))
        agent.session.submitFromUserAction()
        assertEquals(before, fixture.commandRequests)
        assertTrue(agent.session.ui.value.messages.isEmpty())
    }

    @Test fun tombstoneBeforeSendBlocksProviderAndDoesNotReviveLocalHistory() = runBlocking {
        send("read tasks")
        val thread = requireNotNull(agent.session.ui.value.threadId)
        val space = SyncSpaceId("tombstone-watch-space"); enrollForTest(space)
        persistTombstone(space, thread)
        val count = fixture.commandRequests
        agent.session.editDraft("read tasks again"); agent.session.submitFromUserAction()
        assertEquals("THREAD_TOMBSTONED", agent.session.ui.value.redactedCode)
        assertEquals(count, fixture.commandRequests)
        assertEquals(1, agent.session.ui.value.messages.count { it.role == AgentMessageRole.USER })
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
    }

    @Test fun tombstoneArrivingDuringProviderCallBlocksFollowingContinuationSend() = runBlocking {
        agent.session.newConversationFromUserAction()
        val thread = requireNotNull(agent.session.ui.value.threadId)
        val space = SyncSpaceId("late-tombstone-watch-space"); enrollForTest(space)
        val barrier = java.util.concurrent.CountDownLatch(1); fixture.commandBarrier = barrier
        agent.session.editDraft("read tasks")
        val flight = scope.launch { agent.session.submitFromUserAction() }
        try {
            compose.waitUntil(10000) { fixture.commandRequests == 1 }
            persistTombstone(space, thread)
        } finally { barrier.countDown() }
        withTimeout(15000) { flight.join() }
        assertEquals(1, fixture.commandRequests)
        assertEquals("THREAD_TOMBSTONED", agent.session.ui.value.redactedCode)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
    }

    private suspend fun persistTombstone(space: SyncSpaceId, thread: AgentThreadId) {
        val replica = AgentReplicaId("01900000-0000-7000-8000-000000000328")
        val payload = SyncPayloadV3(operation = AgentSyncOperation(MutationId("01900000-0000-7000-8000-000000000329"),
            AgentDvvSnapshot(emptyList(), AgentDot(replica, 0)), AgentHlcSnapshot(0, 0, replica), ThreadDeleted(AgentThreadSyncId(thread.value))))
        val persistence = RoomAgentSyncPersistence(database)
        persistence.provisionLocalReplica(space, AgentReplicaId("01900000-0000-7000-8000-000000000330"))
        persistence.acceptInbound(space, payload)
        persistence.markHandled(space, payload.operation.operationId.value)
        assertTrue(RoomAgentSyncPersistence(database).threadHistoryProjection(space, AgentThreadSyncId(thread.value)).tombstoned)
    }

    @Test fun offlineConfirmationRetainsSuccessfulBusinessTruthAndNeverReplays() = runBlocking {
        send("create task")
        agent.readiness.network.close()
        assertNull(agent.readiness.requestSnapshot())
        val count = fixture.commandRequests
        confirm(true); assertLinkedSuccess()
        assertEquals(count, fixture.commandRequests)
        assertEquals(WearAgentUiPhase.PROVIDER_UNAVAILABLE, agent.session.ui.value.phase)
        agent.readiness.network.start(); agent.readiness.refresh()
        delay(300)
        assertEquals(count, fixture.commandRequests)
        assertEquals(1, RoomMutationJournalRepository(database).timeline().size)
    }

    @Test fun conversationConsentAndCredentialReadinessNeverReplaceV2Gate() = runBlocking {
        val space = SyncSpaceId("watch-isolated-space")
        enrollForTest(space)
        val transport = RoomAgentSyncTransportPersistence(database)
        assertFalse(transport.conversationConsent(space))
        transport.setConversationConsent(space, true, true)
        assertFalse(WearV2BusinessWriteGate(RoomLocalEnrollmentRepository(database), agent.state).mayCommit())
        send("create task"); confirm(true)
        assertTrue(RoomMutationJournalRepository(database).timeline().isEmpty())
        assertTrue(RoomTaskRepository(database).observeTasks().first().isEmpty())
        assertTrue(transport.outboundRecords(space).isEmpty())
        assertFalse(agent.state.syncAgentOriginEnabled(space))
        assertTrue(agent.state.localHistoryTurns(requireNotNull(agent.session.ui.value.threadId)).isNotEmpty())
    }

    @Test fun realCredentialWipeStopsNewRequestButPreservesExistingLocalConfirmation() = runBlocking {
        send("create task")
        val space = SyncSpaceId("watch-credential-space")
        val enrollment = enrollForTest(space)
        agent.state.setSyncAgentOriginEnabled(space, true)
        agent.readiness.settings.setAiEntryFromExplicitUserAction(false)
        val config = requireNotNull(agent.state.providerConfig(configId)).copy(baseUrl = "https://provider.example/v1")
        agent.state.saveProviderConfig(config)
        val secrets = AndroidKeystoreSecureStore(context)
        val hpke = TinkPairingHpke()
        val provisioning = ProviderCredentialProvisioningService(RoomProviderCredentialProvisioningRepository(database), secrets, hpke,
            enrollment.deviceId, requireNotNull(secrets.privateKey(enrollment.hpkePrivateKeyReference)))
        val source = DeviceId("authenticated-test-provisioner")
        val revision = provisioning.reserveForExplicitUser(config.provisioningBinding(config.id, true), source)
        val envelope = hpke.encryptProviderCredential(enrollment.hpkePublicKey, ProviderCredentialPlaintextV1(targetDeviceId = enrollment.deviceId,
            providerConfigId = configId.value, credentialRevision = requireNotNull(revision.liveRevision), credentialSecretBase64Url = encodeCanonicalBase64Url("public-fixture-secret".encodeToByteArray())))
        val comparison = provisioning.compare(source, envelope)
        provisioning.approveComparisonByExplicitLocalUser(comparison, comparison.comparisonCode)
        provisioning.installApproved(source, envelope)
        val installedRef = requireNotNull(agent.state.providerConfig(configId)?.credentialReference)
        agent.close()
        agent = WearAgentRuntimeComposition.createActive(context, database, scope, d8, { it(false) }, "en-US", testIds, testClock)
        withContext(Dispatchers.Main.immediate) { agent.readiness.start() }
        agent.refreshSelectedBinding(); agent.foregroundChanged(true)
        assertTrue(agent.readiness.readiness.value.providerReady)
        assertFalse(agent.readiness.settings.userEnabledAiEntry.value)
        provisioning.disableAndWipe(configId.value)
        assertNull(secrets.readProviderSecret(installedRef))
        agent.refreshSelectedBinding()
        agent.readiness.settings.setAiEntryFromExplicitUserAction(true)
        assertNull(agent.readiness.requestSnapshot())
        assertEquals(WearProviderRuntimeState.INSTALL_BLOCKED, agent.readiness.readiness.value.runtimeState)
        agent.session.restoreRecentThread(); render()
        val count = fixture.commandRequests
        confirm(true); assertLinkedSuccess()
        assertEquals(count, fixture.commandRequests)
        assertEquals(AgentToolResultStatus.SUCCESS, agent.session.ui.value.results.single().status)
        assertTrue(RoomAgentSyncTransportPersistence(database).outboundRecords(space).isEmpty())
    }

    private suspend fun enrollForTest(space: SyncSpaceId): LocalEnrollmentState.Active {
        val secrets = AndroidKeystoreSecureStore(context)
        val hpke = secrets.generatePairingDeviceKey(); val amk = secrets.generateAccountMasterKey(); val device = secrets.generate()
        cleanupReferences += listOf(hpke.privateKeyReference, amk, device.reference)
        return LocalEnrollmentState.Active(AccountId("watch-test"), DeviceId("watch-test-device"), EnrollmentRequestId("watch-test-request"),
            hpke.publicKey, hpke.privateKeyReference, space, amk, device.reference).also { RoomLocalEnrollmentRepository(database).saveActive(it) }
    }

    private suspend fun assertLinkedSuccess() {
        val thread = requireNotNull(agent.session.ui.value.threadId)
        val committed = RoomMutationJournalRepository(database).timeline().single()
        val origin = committed.operation.origin as MutationOrigin.Agent
        val action = requireNotNull(agent.state.action(AgentActionId(origin.agentActionId)))
        assertEquals(AgentActionStatus.SUCCEEDED, action.status)
        assertEquals(committed.operation.mutationId, action.mutationIds.single().value)
        assertEquals(action.mutationIds, agent.state.toolResults(thread).single().mutationIds)
        assertEquals(committed.operation.mutationId, RoomMutationJournalRepository(database).diff(committed.operation.mutationId).single().mutationId)
        assertEquals(1, RoomTaskRepository(database).observeTasks().first().size)
        val transport = RoomAgentSyncTransportPersistence(database); val space = SyncSpaceId("no-consent-watch")
        assertFalse(transport.conversationConsent(space)); assertTrue(transport.outboundRecords(space).isEmpty())
    }
}

/** Deterministic HTTP fixture only: replies are real structured calls, interpreted by the production adapter/runtime. */
private class StructuredFixture : AutoCloseable {
    private val socket = ServerSocket(0, 20, InetAddress.getByName("0.0.0.0"))
    val port get() = socket.localPort
    val bodies = CopyOnWriteArrayList<String>()
    val authorizationHeaders = CopyOnWriteArrayList<String?>()
    @Volatile var commandRequests = 0
    @Volatile var probeRequests = 0
    @Volatile var probeSupported = true
    @Volatile var probeStatus = 200
    @Volatile var updateTaskId: String? = null
    @Volatile var commandBarrier: java.util.concurrent.CountDownLatch? = null
    @Volatile var redirectCommands: String? = null
    private val worker = Thread {
        while (!socket.isClosed) try {
            socket.accept().use { connection ->
                val input = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                var length = 0
                var authorization: String? = null
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    if (line.startsWith("Authorization:", true)) authorization = line.substringAfter(':').trim()
                }
                val body = CharArray(length); var read = 0
                while (read < length) { val count = input.read(body, read, length - read); if (count < 0) break; read += count }
                val text = body.concatToString(); bodies += text
                authorizationHeaders += authorization
                val request = Json.parseToJsonElement(text).jsonObject
                val messages = request["messages"]!!.jsonArray
                val probe = messages.any { it.jsonObject["content"]?.jsonPrimitive?.content == "Call d9_capability_probe now." }
                val chatOnly = !probe && request["tools"]?.jsonArray.orEmpty().isEmpty()
                val tool = if (probe) "d9_capability_probe".takeIf { probeSupported } else if (chatOnly || messages.last().jsonObject["role"]!!.jsonPrimitive.content == "tool") null
                    else messages.last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.jsonObject["content"]!!.jsonPrimitive.content.let {
                        if (it.contains("create")) "task.create" else if (it.contains("update")) "task.update" else "task.list"
                    }
                if (probe) probeRequests++ else { commandRequests++; commandBarrier?.await(15, java.util.concurrent.TimeUnit.SECONDS) }
                val external = tool?.let { "d9_" + it.encodeToByteArray().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }
                val args = if (tool == "task.create") """{"title":"Watch-created task","priority":"NORMAL","estimatedMinutes":30,"remainingMinutes":null,"deadline":null}""" else if (tool == "task.update") """{"taskId":"$updateTaskId","title":"Agent edit","status":"OPEN","priority":"NORMAL","estimatedMinutes":null,"completedMinutes":0,"remainingMinutes":null,"deadline":null}""" else if (tool == "task.list") """{"status":null}""" else "{}"
                val message = if (external == null) buildJsonObject { put("role", "assistant"); put("content", if (chatOnly) CHAT_ONLY_REPLY else "Structured fixture complete") }
                else buildJsonObject { put("role", "assistant"); putJsonArray("tool_calls") { add(buildJsonObject {
                    put("id", if (probe) "probe" else "local-call"); put("type", "function"); putJsonObject("function") { put("name", external); put("arguments", args) }
                }) } }
                val response = buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("message", message) }) } }.toString().encodeToByteArray()
                connection.getOutputStream().apply {
                    val redirect = if (probe) null else redirectCommands
                    if (redirect != null) write("HTTP/1.1 302 Found\r\nLocation: $redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    else { val status = if (probe) probeStatus else 200; write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".encodeToByteArray()); write(response) }
                    flush()
                }
            }
        } catch (_: Exception) { if (!socket.isClosed) throw IllegalStateException("Deterministic Provider fixture failed") }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); worker.join(2000) }
    companion object { const val CHAT_ONLY_REPLY = "I would create or update that task. This is conversation prose only." }
}
