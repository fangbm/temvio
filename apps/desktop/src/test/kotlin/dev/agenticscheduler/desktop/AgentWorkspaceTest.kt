package dev.agenticscheduler.desktop

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.*
import dev.agenticscheduler.agent.provider.ProviderProbeResult
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.fixtures.D10AgentFixtureGraph
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.domain.planning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

class AgentWorkspaceTest {
    @Test fun persistedThreadsLoadWithoutCreatingOrSending() = fixture { f ->
        val c=f.workspace(); c.refresh()
        check(c.selectedThread==f.base.threadId); check(c.threads.size==1); check(f.requests==0)
        check(c.messages==f.base.agent.messages(f.base.threadId)); check(c.loaded)
    }
    @Test fun explicitNewConversationAndSwitchKeepSessionDraftsOutOfHistory() = fixture { f ->
        val c=f.workspace();c.refresh();c.editCommand("unsent first")
        c.newConversation();val other=checkNotNull(c.selectedThread);check(other!=f.base.threadId)
        check(c.pending==null);check(f.base.agent.messages(other).isEmpty());c.editCommand("unsent second")
        c.selectThread(f.base.threadId);check(c.command=="unsent first");c.selectThread(other);check(c.command=="unsent second")
        check(f.requests==0);check(f.base.agent.messages(f.base.threadId).size==2)
    }
    @Test fun explicitSendPersistsExactUserAndAssistantWithoutAnchorHack() = fixture { f ->
        val c=f.workspace();c.refresh();c.editCommand("A normal no-anchor question")
        f.chat("An ordinary assistant reply");c.send()
        check(c.command.isEmpty());check(c.messages.takeLast(2).map {it.content}==listOf("A normal no-anchor question","An ordinary assistant reply"))
        check(c.messages.last().role==AgentMessageRole.ASSISTANT);check(f.requests==1)
    }
    @Test fun duplicateSendIsRejectedWhileFirstExplicitActionIsInFlight() = fixture { f ->
        val c=f.workspace();c.refresh();c.editCommand("only once")
        f.requestEntered=CompletableDeferred();f.requestGate=CompletableDeferred()
        coroutineScope {
            val first=async {c.send()};f.requestEntered!!.await();check(c.busy)
            c.send();check(f.requests==1);f.requestGate!!.complete(Unit);first.await()
        }
        check(c.messages.count {it.content=="only once"}==1)
    }
    @Test fun unsupportedToolsStayChatOnlyAndProseCannotCreateAnyMutation() = fixture { f ->
        f.probeResult=ProviderProbeResult.Unsupported
        val c=f.workspace();c.refresh();val before=f.base.journal.timeline().size
        c.editCommand("Create a task and update my event");c.send();val observation=f.observation!!;c.observeProbe(observation.first,observation.second)
        check(c.providerAvailability==AgentProviderAvailability.CHAT_ONLY);check(f.lastToolCount==0)
        check(c.calls.isEmpty());check(c.actions.isEmpty());check(f.base.journal.timeline().size==before)
        c.editCommand("more chat");check(c.canSend)
    }
    @Test fun supportedReadUsesTypedToolAndZeroBusinessWrites() = fixture { f ->
        val c=f.workspace();c.refresh();val before=f.base.journal.timeline().size
        f.propose("task.list","""{"status":null}""");c.editCommand("Read tasks");c.send()
        check(c.results.single().status==AgentToolResultStatus.SUCCESS);check(c.results.single().mutationIds.isEmpty())
        check(f.lastToolCount>0);check(f.base.journal.timeline().size==before)
        check(agentSnapshotFacts(c.results.single().resultJson).any {"Prepare research notes" in it.value})
    }
    @Test fun waitingConfirmationRestoresExactPreviewOnCoordinatorRemount() = fixture { f ->
        val c=pendingCreate(f);val call=c.pending!!;val before=f.requests
        val remount=f.workspace();remount.refresh()
        check(remount.pending==call);check(remount.overlay==AgentOverlay.CONFIRMATION);check(f.requests==before)
        remount.closeOverlay();check(remount.pending==call);remount.reviewPending();check(remount.pending?.previewJson==call.previewJson)
        check(agentSnapshotFacts(call.previewJson).any {it.value=="Synthetic proposed task"})
        check(agentSnapshotFacts(call.previewJson).none {it.value.startsWith("{")})
    }
    @Test fun waitingBlocksSendSwitchAndNewThreadWithoutLosingDraftOrCall() = fixture { f ->
        val c=pendingCreate(f);val call=c.pending!!;val before=f.requests;val count=c.threads.size
        c.editCommand("attempt while pending");c.send();c.newConversation();c.selectThread(f.base.threadId)
        check(!c.canSend);check(!c.canSwitch);check(c.pending==call);check(f.requests==before);check(c.threads.size==count)
    }
    @Test fun exactConfirmAndRepeatedConfirmCommitOnlyOnce() = fixture { f ->
        val c=pendingCreate(f);val call=c.pending!!;val before=f.base.journal.timeline().size
        c.confirm(AgentToolCallId(f.base.ids.next()),true);check(c.pending==call)
        c.confirm(call.id,true);c.confirm(call.id,true)
        check(c.pending==null);check(c.results.single().callId==call.id);check(c.results.single().status==AgentToolResultStatus.SUCCESS)
        check(c.results.single().mutationIds.size==1);check(f.base.journal.timeline().size==before+1)
        check(f.base.tasks.observeTasks().first().count {it.title=="Synthetic proposed task"}==1)
    }
    @Test fun exactDenyAndRepeatedDenyProduceNoBusinessWrite() = fixture { f ->
        val c=pendingCreate(f);val call=c.pending!!;val before=f.base.journal.timeline().size
        c.confirm(call.id,false);c.confirm(call.id,false)
        check(c.results.single().callId==call.id);check(c.results.single().status==AgentToolResultStatus.PERMISSION_DENIED)
        check(c.calls.single().state==AgentToolCallState.DENIED);check(f.base.journal.timeline().size==before)
    }
    @Test fun taskUpdateStaleRevalidationDoesNotOverwriteNewerFacts() = fixture { f ->
        val task=f.base.tasks.observeTasks().first().single()
        val c=f.workspace();c.refresh();f.propose("task.update",updateInput(task.id.value,"Agent change"));c.editCommand("Update task");c.send()
        val call=c.pending!!
        check(f.base.taskEditor.update(UpdateTaskInput(task.id,"Newer user edit",task.status,task.priority,task.effort.estimated,task.effort.completed,task.effort.remaining,null),expectedBefore=task) is EditingResult.Success)
        val before=f.base.journal.timeline().size;c.confirm(call.id,true)
        check(c.results.single().status==AgentToolResultStatus.STALE);check(f.base.tasks.getTask(task.id)?.title=="Newer user edit")
        check(f.base.journal.timeline().size==before);check(agentResultCopy(c.results.single().status).contains("new proposal"))
    }
    @Test fun invalidInputAndNotFoundRemainDistinctNoWriteResults() = fixture { f ->
        val c=f.workspace();c.refresh();val before=f.base.journal.timeline().size
        f.propose("task.create",f.createInput().replace("\"NORMAL\"","\"UNKNOWN\""));c.editCommand("invalid");c.send()
        check(c.results.last().status==AgentToolResultStatus.INVALID_INPUT)
        f.propose("task.get","""{"taskId":"${f.base.ids.next()}"}""");c.editCommand("missing");c.send()
        check(c.results.last().status==AgentToolResultStatus.NOT_FOUND);check(f.base.journal.timeline().size==before)
    }
    @Test fun localPolicyPersistsAndNextToolUsesExistingPermissionEngine() = fixture { f ->
        val c=f.workspace();c.refresh();val before=f.base.journal.timeline().size
        c.setPermission(AgentToolCapability.LOW_RISK_CREATE,AgentPermissionMode.DENY)
        check(f.base.agent.permissionPolicy().modeFor(AgentToolCapability.LOW_RISK_CREATE)==AgentPermissionMode.DENY)
        check(f.requests==0);check(f.base.journal.timeline().size==before)
        f.propose("task.create",f.createInput());c.editCommand("attempt create");c.send()
        check(c.results.single().status==AgentToolResultStatus.PERMISSION_DENIED);check(c.pending==null);check(f.base.journal.timeline().size==before)
        val remount=f.workspace();remount.refresh();check(remount.policy?.modeFor(AgentToolCapability.LOW_RISK_CREATE)==AgentPermissionMode.DENY)
    }
    @Test fun denyOnlyCannotBeElevatedAndNoPolicyToolExists() = fixture { f ->
        val c=f.workspace();c.refresh()
        listOf(AgentToolCapability.BULK_CHANGE,AgentToolCapability.DESTRUCTIVE,AgentToolCapability.EXTERNAL_SIDE_EFFECT).forEach {
            check(legalPermissionModes(it)==listOf(AgentPermissionMode.DENY));c.setPermission(it,AgentPermissionMode.ALLOW_DIRECT)
            check(f.base.agent.permissionPolicy().modeFor(it)==AgentPermissionMode.DENY)
        }
        f.propose("permission.change","{}");c.editCommand("change permissions");c.send()
        check(c.diagnostic=="UNKNOWN_PROVIDER_TOOL_NAME");check(f.base.agent.permissionPolicy().modeFor(AgentToolCapability.DESTRUCTIVE)==AgentPermissionMode.DENY)
    }
    @Test fun providerSwitchNeverCreatesThreadOrRewritesConversation() = fixture { f ->
        val c=f.workspace();c.refresh();val oldMessages=c.messages;val oldThreads=c.threads
        val config=f.base.agent.providerConfigs().single();val other=config.copy(id=ProviderConfigId(f.base.ids.next()),model="Other model")
        f.base.agent.saveProviderConfig(other);f.base.agent.selectProviderConfig(other.id);c.refresh()
        check(c.messages==oldMessages);check(c.threads==oldThreads);check(c.selectedThread==f.base.threadId);check(f.requests==0)
        check(c.providerLabel=="Other model")
    }
    @Test fun noProviderMissingCredentialAndCredentialedHttpBlockWithoutSend() = fixture { f ->
        val c=f.workspace();c.refresh();c.editCommand("unsent")
        val config=f.base.agent.providerConfigs().single()
        f.base.agent.selectProviderConfig(null);c.refresh();check(c.providerAvailability==AgentProviderAvailability.NONE);c.send();check(f.requests==0)
        f.base.agent.saveProviderConfig(config.copy(credentialReference=SecretReference("synthetic://missing")));f.base.agent.selectProviderConfig(config.id)
        c.refresh();check(c.providerAvailability==AgentProviderAvailability.CREDENTIAL_UNAVAILABLE);check(!c.canSend)
        f.base.agent.saveProviderConfig(config.copy(baseUrl="http://model.example",credentialReference=SecretReference("synthetic://missing")))
        c.refresh();check(c.providerAvailability==AgentProviderAvailability.INVALID_ENDPOINT);check(!c.canSend)
        check(c.messages.none {"synthetic://missing" in it.content})
    }
    @Test fun credentialFreeHttpIsTruthfullyPlaintextAndDoesNotRewriteEndpoint() = fixture { f ->
        val config=f.base.agent.providerConfigs().single().copy(baseUrl="http://lan.example/v1")
        f.base.agent.saveProviderConfig(config);val c=f.workspace();c.refresh();c.editCommand("chat")
        check(c.plaintextTransport);check(c.canSend);check(f.base.agent.providerConfig(config.id)?.baseUrl=="http://lan.example/v1")
    }
    @Test fun unavailableProbeBlocksUntilExplicitRetryButDoesNotEraseThread() = fixture { f ->
        val c=f.workspace();c.refresh();c.editCommand("retain draft")
        val config=f.base.agent.providerConfigs().single()
        listOf("HTTP_401","INVALID_CONFIG","NETWORK_UNAVAILABLE").forEach { code ->
            c.observeProbe(config,ProviderProbeResult.Unavailable(code));check(!c.canSend)
            c.send();check(f.requests==0);c.refresh();check(!c.canSend)
            c.retryAvailabilityFromUser();check(c.canSend);check(c.command=="retain draft")
        }
        check(f.base.agent.thread(f.base.threadId)!=null)
    }
    @Test fun actualHttpFailureStaysRedactedAndNeverClaimsBusinessCommit() = fixture { f ->
        val c=f.workspace();c.refresh();val before=f.base.journal.timeline().size;f.httpStatus=io.ktor.http.HttpStatusCode.Unauthorized
        c.editCommand("try chat");c.send();check(c.diagnostic=="HTTP_401");check(c.actions.isEmpty())
        check(c.status!!.contains("unavailable"));check(f.base.journal.timeline().size==before)
    }
    @Test fun explicitDeletionRetainsCommittedActionD7AndBusinessWithoutUndo() = fixture { f ->
        val c=pendingCreate(f);c.confirm(c.pending!!.id,true);val action=c.actions.single();val before=f.base.journal.timeline().size
        val thread=checkNotNull(c.selectedThread);c.deleteSelected();check(f.base.agent.thread(thread)!=null)
        c.requestDeletion();c.closeOverlay();check(f.base.agent.thread(thread)!=null)
        c.requestDeletion();c.deleteSelected()
        check(f.base.agent.thread(thread)==null);check(f.base.agent.messages(thread).isEmpty());check(f.base.agent.toolCalls(thread).isEmpty())
        check(f.base.agent.localThreadProvenance(thread)?.state==AgentLocalThreadProvenanceState.DELETED)
        check(f.base.agent.localHistoryTurns(thread).isEmpty());check(f.base.agent.action(action.id)?.mutationIds==action.mutationIds)
        check(f.base.journal.timeline().size==before);check(f.base.tasks.observeTasks().first().any {it.title=="Synthetic proposed task"})
    }
    @Test fun allPersistedResultStatusesHaveDistinctPresentation() = fixture { f ->
        val c=f.workspace();c.refresh()
        AgentToolResultStatus.entries.forEachIndexed { index,status ->
            val call=AgentToolCall(AgentToolCallId(f.base.ids.next()),f.base.threadId,c.messages.first().id,index.toLong(),"task.get","{}",AgentToolCallState.FAILED)
            f.base.agent.saveToolCall(call);f.base.agent.appendToolResult(AgentToolResult(AgentToolResultId(f.base.ids.next()),f.base.threadId,call.id,index.toLong(),status,"""{"status":"${status.name}"}"""))
        }
        c.refresh();check(c.results.map {it.status}.toSet()==AgentToolResultStatus.entries.toSet())
        check(AgentToolResultStatus.entries.map(::agentResultCopy).distinct().size==AgentToolResultStatus.entries.size)
        check(AgentToolCallState.entries.map {readableAgentLabel(it.name)}.distinct().size==6)
    }
    @Test fun eventUpdateAndPlanningProfileUpdateReflectRealTypedCommits() = fixture { f ->
        val event=f.base.events.observeAll().first().first {it.title=="Campus day"}
        val c=f.workspace();c.refresh()
        f.propose("event.update","""{"eventId":"${event.id.value}","title":"Updated campus day","time":{"kind":"ALL_DAY","startDate":"2026-10-06","endDateExclusive":"2026-10-07"},"flexibility":"HARD","pinState":"UNPINNED"}""")
        c.editCommand("update event");c.send();check(c.pending!=null);c.confirm(c.pending!!.id,true)
        check(c.results.last().status==AgentToolResultStatus.SUCCESS);check(f.base.events.get(event.id)?.title=="Updated campus day")
        f.propose("planningProfile.update","""{"planningProfileId":"${f.product.unconfigured.id.value}","name":"Renamed profile","configuration":"UNCONFIGURED"}""")
        c.editCommand("update profile");c.send();check(c.pending!=null);c.confirm(c.pending!!.id,true)
        check(c.results.last().status==AgentToolResultStatus.SUCCESS);check(f.base.profiles.get(f.product.unconfigured.id)?.name=="Renamed profile")
    }
    @Test fun plannerPreviewAndApplyStayProposalThenExplicitCommit() = fixture { f ->
        val c=f.workspace();c.refresh();val before=f.base.journal.timeline().size
        f.propose("planner.previewFullReplan","""{"planningProfileId":"${f.product.configured.id.value}","referenceNow":"2026-10-06T08:00:00Z","horizonStart":"2026-10-06T08:00:00Z","horizonEndExclusive":"2026-10-06T17:00:00Z"}""")
        c.editCommand("preview planner");c.send();val result=c.results.single();check(result.status==AgentToolResultStatus.SUCCESS)
        val branch=Json.parseToJsonElement(result.resultJson).jsonObject["id"]!!.jsonPrimitive.content
        check(f.base.journal.timeline().size==before);check(agentSnapshotFacts(result.resultJson).any {"Mutations" in it.label})
        f.propose("planner.applyBranch","""{"planBranchId":"$branch","applyNow":"2026-10-06T08:00:00Z"}""")
        c.editCommand("apply preview");c.send();check(c.pending!=null);check(f.base.journal.timeline().size==before)
        c.confirm(c.pending!!.id,true);check(c.results.last().status==AgentToolResultStatus.SUCCESS);check(f.base.journal.timeline().size==before+1)
    }
    @Test fun conflictAtConfirmationLeavesDomainAndD7Unchanged() = fixture { f ->
        val c=f.workspace();c.refresh()
        f.propose("planningProfile.update","""{"planningProfileId":"${f.product.configured.id.value}","name":"Proposed profile","configuration":"CONFIGURED","timeZone":"UTC","weeklyAvailability":[{"dayOfWeek":"TUESDAY","start":"09:00","endExclusive":"17:00"}],"minimumFocusBlockMinutes":25,"preferredFocusBlockMinutes":50,"maximumFocusBlockMinutes":90,"allDayEventPolicy":"NON_BLOCKING"}""")
        c.editCommand("update profile");c.send();val call=c.pending!!;f.product.profileConflict()
        val before=f.base.journal.timeline().size;c.confirm(call.id,true)
        check(c.results.single().status==AgentToolResultStatus.CONFLICT)
        check(f.base.profiles.get(f.product.configured.id)==f.product.configured);check(f.base.journal.timeline().size==before)
    }
    @Test fun databaseReopenRestoresPendingWithoutReplayOrDraftPersistence() {
        val file=File.createTempFile("d10-agent-restart-", ".db")
        val first=openDesktopDatabase(file.absolutePath);val f=D10AgentFixtureGraph(first)
        try {
            val call=runBlocking {f.seed();pendingCreate(f).pending!!};val count=f.requests
            first.close()
            val reopened=openDesktopDatabase(file.absolutePath)
            try {
                val repo=dev.agenticscheduler.database.repository.RoomAgentStateRepository(reopened)
                val c=AgentWorkspaceCoordinator(repo,f.run,f.base.secrets)
                runBlocking {c.refresh()}
                check(c.pending==call);check(c.overlay==AgentOverlay.CONFIRMATION);check(c.command.isEmpty());check(f.requests==count)
            } finally {reopened.close()}
        } finally {f.close();first.close();file.delete()}
    }
    @Test fun historyUndoIsAnExplicitCompensationNotEvidenceDeletion() = fixture { f ->
        val task=f.base.tasks.observeTasks().first().single();val c=f.workspace();c.refresh()
        f.propose("task.update",updateInput(task.id.value,"Changed by Agent"));c.editCommand("update task");c.send();c.confirm(c.pending!!.id,true)
        val mutation=c.results.single().mutationIds.single();val before=f.base.journal.timeline().size
        f.propose("history.undo","""{"mutationId":"${mutation.value}"}""");c.editCommand("undo that change");c.send();check(c.pending!=null)
        check(f.base.journal.timeline().size==before);c.confirm(c.pending!!.id,true)
        check(c.results.last().status==AgentToolResultStatus.SUCCESS);check(f.base.tasks.getTask(task.id)?.title==task.title)
        check(f.base.journal.timeline().size==before+1);check(f.base.journal.timeline().any {it.operation.mutationId==mutation.value})
    }

    private suspend fun pendingCreate(f:D10AgentFixtureGraph):AgentWorkspaceCoordinator = f.workspace().also {c ->
        c.refresh();f.propose("task.create",f.createInput());c.editCommand("Propose a task");c.send();check(c.pending!=null)
    }
    private fun updateInput(id:String,title:String)="""{"taskId":"$id","title":"$title","status":"OPEN","priority":"NORMAL","estimatedMinutes":120,"completedMinutes":0,"remainingMinutes":120,"deadline":null}"""
    private fun fixture(test:suspend (D10AgentFixtureGraph)->Unit) {
        val file=File.createTempFile("d10-agent-", ".db");val db=openDesktopDatabase(file.absolutePath);val f=D10AgentFixtureGraph(db)
        try {runBlocking {f.seed();test(f)}} finally {f.close();db.close();file.delete()}
    }
}
