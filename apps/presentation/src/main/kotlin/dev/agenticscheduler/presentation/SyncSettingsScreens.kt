package dev.agenticscheduler.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.agenticscheduler.application.sync.RecoverySecret
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class SettingsSection { GENERAL, PLANNING, SYNC_SECURITY, PROVIDER, AGENT_PERMISSIONS }

@Composable
fun SettingsHub(section:SettingsSection,onSection:(SettingsSection)->Unit,dark:Boolean,onTheme:()->Unit,
    planning:@Composable ()->Unit,sync:@Composable ()->Unit,onProvider:()->Unit,
    permissions:@Composable ()->Unit = {},provider:@Composable ()->Unit = {}) {
    Column(Modifier.fillMaxSize().testTag("settings-hub")) {
        FlowRow(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            SettingsSection.entries.forEach {s -> NavigationControl(s.name.lowercase().replace('_',' '),section==s,{ if(s==SettingsSection.PROVIDER) onProvider() else onSection(s) }) }
        }
        when(section) {
            SettingsSection.GENERAL -> LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item {SectionHeading("Appearance", "Device session only; opening Settings never writes business data")}
                item {ActionButton(onTheme,role=ActionRole.SECONDARY) {Text(if(dark) "Use Light theme" else "Use Dark theme")};Text("Theme choice lasts for this app session. No durable preference is claimed.")}
                item {SectionHeading("Planning", "Explicit profiles and session-local previews");ActionButton({onSection(SettingsSection.PLANNING)},role=ActionRole.TERTIARY) {Text("Open planning profiles")}}
                item {SectionHeading("Sync & Security", "Enrollment, problems and explicit actions");ActionButton({onSection(SettingsSection.SYNC_SECURITY)},role=ActionRole.TERTIARY) {Text("Open Sync / Security")}}
                item {SectionHeading("Agent / Provider", "Existing Provider and local Agent controls");ActionButton(onProvider,role=ActionRole.TERTIARY) {Text("Open Provider settings")}}
                item {ActionButton({onSection(SettingsSection.AGENT_PERMISSIONS)},role=ActionRole.TERTIARY) {Text("Agent permissions")}}
                item {SectionHeading("About", "Planner / History / Sync remain application-owned");Text("Local database at-rest protection: OD-012 remains an OPEN production gate. Secure transport does not imply encrypted local SQLite.")}
            }
            SettingsSection.PLANNING -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {planning()}
            SettingsSection.SYNC_SECURITY -> sync()
            SettingsSection.PROVIDER -> provider() // Platform-owned settings composition; no credential state copied here.
            SettingsSection.AGENT_PERMISSIONS -> permissions()
        }
    }
}

@Composable
fun SyncSecurityScreen(c:SyncSecurityScreenCoordinator,scope:CoroutineScope,runtime:SyncRuntimeDisplay,onRetry:()->Unit,
    conversationControls:@Composable ()->Unit,security:SecurityWorkflowCoordinator?=null) {
    LaunchedEffect(c) {c.refresh()}
    val selected = c.selected
    val enrollment = c.enrollment
    val openConflicts = c.openConflicts
    val loaded = c.loaded
    FeatureListDetail(selected!=null,c::closeDetail,list={
        LazyColumn(Modifier.fillMaxSize().padding(20.dp).testTag("sync-security"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {SectionHeading("Sync / Security", "Business sync and Agent history remain separate")}
            item {
                when {
                    runtime.stoppedReason!=null -> {StatusMessage("Automatic sync stopped",runtime.stoppedReason);ActionButton(onRetry,modifier=Modifier.testTag("sync-explicit-retry")) {Text("Retry sync")}}
                    runtime.configured -> StatusMessage("Foreground catch-up enabled", "No terminal stopped reason is reported. This does not claim queues drained or all devices caught up.")
                    else -> StatusMessage("No configured transport", "Local data remains available. An ACTIVE enrollment without a server route is offline; no sync completion is claimed.")
                }
                Text("Held outbound writes do not stop independent inbound catch-up. Per-direction progress is unavailable from the current status API.",style=MaterialTheme.typography.bodySmall)
                ActionButton({scope.launch {c.refresh()}},enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Refresh sync metadata")}
                c.message?.let {Text(it)}
            }
            item {SectionHeading("This installation", "Enrollment metadata only; keys and credentials are never displayed")}
            if(!loaded) item {Text("Loading enrollment…")}
            else if(enrollment.isEmpty()) item {Text("Not enrolled. No active SyncSpace.")}
            items(enrollment) {e ->
                var technical by remember(e.deviceId) {mutableStateOf(false)}
                Text("${e.state} enrollment${if(e.space!=null) " · Personal SyncSpace" else " · admission pending"}")
                ActionButton({technical=!technical},role=ActionRole.TERTIARY) {Text(if(technical) "Hide installation identity" else "Installation identity")}
                if(technical) {Text("Account · ${e.accountId.value}");Text("Device · ${e.deviceId.value}");e.space?.let {Text("SyncSpace · ${it.value}")}}
            }
            item {SectionHeading("Business sync conflicts", "Not Calendar overlaps or Agent-history conflicts")}
            if(loaded && openConflicts.isEmpty()) item {Text("No OPEN business conflicts reported for the selected SyncSpace.")}
            items(openConflicts,key={it.id}) {conflict ->
                PresentationCard {
                    Text("${conflict.status} · ${conflict.kind}",style=MaterialTheme.typography.titleMedium)
                    Text(conflict.entities.joinToString(" · ") {it.entityKind.name.lowercase().replace('_',' ')})
                    Text("${conflict.participants.size} participants · ${conflict.entities.flatMap {it.groups}.distinct().joinToString()}")
                    ActionButton({scope.launch {c.inspect(conflict.id)}},enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Inspect business conflict")}
                }
            }
            item {
                SectionHeading("Agent-origin business compatibility", "V2 business writes · independent of conversation V3 consent")
                if(c.activeSpace!=null) {
                    Row {Checkbox(c.agentBusinessEnabled,{enabled -> scope.launch {c.setAgentBusinessFromUser(enabled)}},enabled=!c.busy);Text("I confirm every enrolled device supports Agent-origin business writes")}
                    Text(if(c.agentBusinessEnabled) "Agent-origin business compatibility acknowledged." else "Agent-origin business sync compatibility is OFF.")
                } else Text("No single active SyncSpace; this compatibility control is unavailable.")
            }
            item {conversationControls()}
            item {
                SectionHeading("Devices and recovery", "Explicit security workflows; no background approval")
                if(security!=null) SecurityWorkflows(security,scope,c.enrollment.singleOrNull {it.state=="ACTIVE"}?.deviceId)
                else Text("An explicit HTTPS/account configuration is required for security workflows. No endpoint is invented.")
                Text("Pairing approval/admission requires a reviewed end-to-end application orchestration boundary; this surface does not encode or relay key packages.")
            }
            item {Text("Accepted sync content transport uses E2EE. Local SQLite at-rest protection is independently OPEN (OD-012).",style=MaterialTheme.typography.bodySmall)}
        }
    },detail={selected?.let {d ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("business-conflict-detail"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeading("Business conflict", "${d.status} · ${d.kind}")
            Text("${d.participants.size} competing mutations. Provisional presentation is not resolution.")
            d.entities.forEach {Text("${it.entityKind} · ${it.groups.joinToString()}");Text("Entity · ${it.entityId}",style=MaterialTheme.typography.bodySmall)}
            Text("ConflictId · ${d.id}",style=MaterialTheme.typography.bodySmall)
            Text("Provisional MutationId · ${d.provisional.value}",style=MaterialTheme.typography.bodySmall)
            d.participants.forEach {Text("Participant · ${it.value}",style=MaterialTheme.typography.bodySmall)}
            d.resolution?.let {Text("Resolution MutationId · ${it.value}")};d.supersededBy?.let {Text("Superseded by · $it")}
            if(d.status==dev.agenticscheduler.sync.SyncConflictStatus.OPEN)
                StatusMessage("Resolution unavailable", "Typed candidate selection awaits a separately reviewed Application foundation. This OPEN conflict cannot be dismissed or cleared by ordinary editing.")
            else Text("This record is ${d.status}. It is no longer an actionable OPEN conflict.")
            ActionButton({scope.launch {c.inspect(d.id)}},enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Refresh conflict detail")}
            ActionButton(c::closeDetail,enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Close business conflict")}
        }
    }})
}

@Composable
private fun SecurityWorkflows(c:SecurityWorkflowCoordinator,scope:CoroutineScope,localDevice:DeviceId?) {
    // Secret input exists only while this explicit dialog is open; never saved/restored or logged.
    var enteringRecovery by remember {mutableStateOf(false)}
    var revokeTarget by remember {mutableStateOf<DeviceId?>(null)}
    var secretText by remember {mutableStateOf("")}
    var invalid by remember {mutableStateOf(false)}
    val clear:()->Unit={secretText="";enteringRecovery=false;revokeTarget=null;invalid=false}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(localDevice==null) ActionButton({clear();enteringRecovery=true},enabled=!c.busy,role=ActionRole.SECONDARY) {Text("Recover enrollment")}
        else {
            ActionButton({scope.launch {c.refreshDevices()}},enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Load active devices")}
            c.devices.filter {it!=localDevice}.forEach {id -> ActionButton({clear();revokeTarget=id},enabled=!c.busy && !c.hasRotationRetry,role=ActionRole.SECONDARY) {Text("Revoke device ${id.value}")} }
        }
        if(c.hasRotationRetry) ActionButton({scope.launch {c.retryRotationFromUser()}},enabled=!c.busy) {Text("Retry exact prepared rotation")}
        c.message?.let {Text(it)}
    }
    if(enteringRecovery || revokeTarget!=null) AlertDialog(onDismissRequest=clear,
        title={Text(if(revokeTarget==null) "Recover this installation" else "Confirm device revocation")},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            if(revokeTarget!=null) Text("Revokes server access and rotates future encryption keys. Material previously possessed by that device is not erased.")
            else Text("Use the real existing Recovery Secret. No replacement phrase or PIN is created here.")
            OutlinedTextField(secretText,{secretText=it;invalid=false},label={Text("Recovery Secret")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            if(invalid) Text("Enter the canonical existing Recovery Secret; input is not logged.")
        }},confirmButton={ActionButton({
            val secret=try {RecoverySecret(secretText.trim())} catch (_:IllegalArgumentException) {null}
            if(secret==null) invalid=true else {val target=revokeTarget;clear();scope.launch {if(target==null)c.recoverFromUser(secret) else c.revokeFromUser(target,secret,true)}}
        },enabled=!c.busy) {Text(if(revokeTarget==null) "Recover from secret" else "Confirm revoke and rotate")}},
        dismissButton={ActionButton(clear,role=ActionRole.TERTIARY) {Text("Cancel security action")}})
}

/** Restricted recovery surface for a blocked startup; no Domain reads or writes are mounted. */
@Composable
fun SecurityRecoveryPanel(c:SecurityWorkflowCoordinator,scope:CoroutineScope) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        SectionHeading("Restore enrollment", "Business screens stay guarded until the existing Application recovery succeeds")
        SecurityWorkflows(c,scope,null)
    }
}
