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
import androidx.compose.ui.unit.dp
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Instant

@Composable
fun PlannerWorkspaceScreen(coordinator: PlannerWorkspaceCoordinator, scope: CoroutineScope) {
    LaunchedEffect(coordinator) { coordinator.refresh() }
    val preview = coordinator.preview
    val draft = coordinator.draft
    val blocks = coordinator.focusBlocks
    val sourceStatus = coordinator.sourceStatus
    val message = coordinator.message
    val busy = coordinator.busy
    FeatureListDetail(preview!=null,coordinator::cancelPreview,list={
    LazyColumn(Modifier.fillMaxSize().padding(20.dp).testTag("planner-workspace"),
        verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom=24.dp)) {
        item { SectionHeading("Plan your time", "Source facts → session preview → explicit Apply") }
        item { PlanningProfiles(coordinator,scope) }
        item {
            SectionHeading("Request inputs", "Reference and ranges are explicit Instants; no hidden horizon")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PlannerMode.entries.forEach { mode -> NavigationControl(if (mode == PlannerMode.FULL_REPLAN) "Full Replan" else "Local Reflow",coordinator.draft.mode == mode,
                    { if (!coordinator.busy) coordinator.draft = coordinator.draft.copy(mode=mode) }) }
            }
            RequestField("Reference Instant",coordinator.draft.referenceNow,coordinator.busy,"planner-reference") { coordinator.draft=coordinator.draft.copy(referenceNow=it) }
            RequestField("Horizon start",coordinator.draft.horizonStart,coordinator.busy,"planner-horizon-start") { coordinator.draft=coordinator.draft.copy(horizonStart=it) }
            RequestField("Horizon end (exclusive)",coordinator.draft.horizonEnd,coordinator.busy,"planner-horizon-end") { coordinator.draft=coordinator.draft.copy(horizonEnd=it) }
        }
        if (draft.mode == PlannerMode.LOCAL_REFLOW) {
            item {
                SectionHeading("Local Reflow", "Explicit affected blocks / disrupted ranges; unrelated blocks stay fixed")
                RequestField("Search start",coordinator.draft.searchStart,coordinator.busy,"planner-search-start") { coordinator.draft=coordinator.draft.copy(searchStart=it) }
                RequestField("Search end (exclusive)",coordinator.draft.searchEnd,coordinator.busy,"planner-search-end") { coordinator.draft=coordinator.draft.copy(searchEnd=it) }
                RequestField("Disrupted ranges: start / end per line",coordinator.draft.disruptedRanges,coordinator.busy,"planner-disruptions",false) { coordinator.draft=coordinator.draft.copy(disruptedRanges=it) }
            }
            items(blocks,key={it.id.value}) { block ->
                Row {
                    Checkbox(block.id in coordinator.draft.affectedIds,{ enabled -> if (!coordinator.busy) coordinator.draft=coordinator.draft.copy(affectedIds=if(enabled) coordinator.draft.affectedIds+block.id else coordinator.draft.affectedIds-block.id) },enabled=!coordinator.busy)
                    Column { Text("Affected FocusBlock · ${coordinator.tasks.firstOrNull { it.id == block.taskId }?.title ?: "Task"}"); Text(block.time.displayRange(),style=MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            sourceStatus?.let { StatusMessage("Source facts",it) }
            message?.let { StatusMessage("Planner result",it) }
            ActionButton({ scope.launch { coordinator.requestPreview() } },Modifier.testTag("planner-request-preview"),enabled=!coordinator.busy && coordinator.draft.profileId!=null) { Text("Preview ${if(coordinator.draft.mode==PlannerMode.FULL_REPLAN) "Full Replan" else "Local Reflow"}") }
            if (busy) Text("Working…")
        }
    }
    },detail={
    LazyColumn(Modifier.fillMaxSize().padding(20.dp).testTag("planner-preview"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        when (val result=preview) {
            is PlannerPreview.Applicable -> {
                val branch=result.branch
                item {
                    SectionHeading("Proposed changes", "${branch.status} · ${branch.mutations.size} FocusBlock changes · not Active Calendar")
                    Text("${if(branch.originalRequest is PlanningRequest.FullReplan) "Full Replan" else "Local Reflow"} · ${branch.baseFacts.profile.name}")
                    Text("Horizon ${branch.baseFacts.horizon.start} → ${branch.baseFacts.horizon.endExclusive}")
                    Text("Preview is lost on process restart; run a fresh preview.",style=MaterialTheme.typography.bodySmall)
                }
                items(coordinator.placements(branch)) { row ->
                    Surface(color=LocalTemvioColors.current.container,shape=MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(row.kind,style=MaterialTheme.typography.titleMedium); Text(row.task)
                            row.before?.let { Text("Before · $it") }; row.after?.let { Text("Proposed · $it") }
                            if(row.kind=="Delete FocusBlock") Text("Removed from the active schedule only after Apply.")
                        }
                    }
                }
                items(branch.issues) { Text(plannerIssueLabel(it)) }
                items(branch.explanations) { explanation -> Text("Decision criteria · ${explanation.criteria.joinToString { it.name.lowercase().replace('_',' ') }}",style=MaterialTheme.typography.bodySmall) }
                item {
                    var applyTime by remember(branch.id) { mutableStateOf("") }
                    RequestField("Apply reference Instant",applyTime,coordinator.busy,"planner-apply-time") { applyTime=it }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ActionButton({ Instant.parseOrNull(applyTime.trim())?.let { time -> scope.launch { coordinator.apply(time) } } },Modifier.testTag("planner-apply"),enabled=!coordinator.busy && branch.status==PlanBranchStatus.DRAFT && Instant.parseOrNull(applyTime.trim())!=null) { Text("Apply preview") }
                        ActionButton({coordinator.cancelPreview()},role=ActionRole.SECONDARY,enabled=!coordinator.busy) { Text("Discard preview") }
                    }
                }
            }
            is PlannerPreview.Infeasible -> { item { SectionHeading("Infeasible", "No applicable branch; Active State unchanged") }; items(result.issues) { Text(plannerIssueLabel(it)) } }
            is PlannerPreview.InvalidInput -> { item { SectionHeading("Invalid input", "No applicable branch; check source facts and request") }; items(result.issues) { Text(plannerIssueLabel(it)) } }
            null -> Unit
        }
        if(preview !is PlannerPreview.Applicable) item {
            ActionButton(coordinator::cancelPreview,enabled=!coordinator.busy,role=ActionRole.SECONDARY) {Text("Close preview result")}
        }
    }
    })
    NewProfileDialogHost(coordinator,scope)
}

@Composable
fun PlanningProfiles(coordinator: PlannerWorkspaceCoordinator, scope: CoroutineScope) {
    LaunchedEffect(coordinator) {if(!coordinator.loaded) coordinator.refresh()}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        SectionHeading("Planning profiles", "Choose explicit availability and scheduling policies")
        if (!coordinator.loaded) Text("Loading profiles…")
        else if (coordinator.profiles.isEmpty()) Text("No profile. Create an Unconfigured profile; no defaults are added automatically.")
        coordinator.profiles.forEach { p ->
            NavigationControl(p.name,coordinator.draft.profileId==p.id,{ if(!coordinator.busy) coordinator.draft=coordinator.draft.copy(profileId=p.id) },Modifier.fillMaxWidth())
        }
        coordinator.profiles.firstOrNull {it.id==coordinator.draft.profileId}?.let { p ->
            when(val c=p.configuration) {
                PlanningProfileConfiguration.Unconfigured -> Text("Unconfigured · automatic scheduling is unavailable.")
                is PlanningProfileConfiguration.Configured -> {
                    Text("${c.timeZone.id} · min ${c.minimumFocusBlock}, preferred ${c.preferredFocusBlock}, max ${c.maximumFocusBlock}")
                    Text("All-day Event policy · ${c.allDayEventPolicy}")
                    c.weeklyAvailability.forEach { Text("${it.dayOfWeek} ${it.start}–${it.endExclusive}") }
                    if(c.weeklyAvailability.isEmpty()) Text("No automatic scheduling availability.")
                }
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ActionButton({coordinator.createName=""},enabled=!coordinator.busy,role=ActionRole.SECONDARY) {Text("New Unconfigured profile")}
            ActionButton({scope.launch {coordinator.refresh()}},enabled=!coordinator.busy,role=ActionRole.TERTIARY) {Text("Refresh source facts")}
        }
        Text("Profile editing is awaiting the reviewed concurrent-save foundation. Stored configuration remains unchanged.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun NewProfileDialogHost(c: PlannerWorkspaceCoordinator,scope: CoroutineScope) {
    if(c.createName==null) return
    val dismiss:()->Unit={ if(!c.busy) {if(!c.createName.isNullOrBlank()) c.discardProfileRequested=true else c.createName=null} }
    AlertDialog(onDismissRequest=dismiss,title={Text("New Unconfigured profile")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        RequestField("Profile name",c.createName.orEmpty(),c.busy,"profile-name") {c.createName=it}
        Text("This creates an explicit Unconfigured profile. No timezone, availability or duration policy is invented.")
        c.message?.let {Text(it)}
    }},confirmButton={ActionButton({scope.launch {c.createUnconfigured()}},enabled=!c.busy && !c.createName.isNullOrBlank()) {Text("Create profile")}},
        dismissButton={ActionButton(dismiss,enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Cancel")}})
    if(c.discardProfileRequested) DraftDiscardPrompt({c.createName=null;c.discardProfileRequested=false},{c.discardProfileRequested=false})
}

@Composable
internal fun RequestField(label:String,value:String,busy:Boolean,tag:String,singleLine:Boolean=true,change:(String)->Unit) {
    OutlinedTextField(value,change,enabled=!busy,label={Text(label)},singleLine=singleLine,modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag(tag))
}

private fun Instant.Companion.parseOrNull(value:String): Instant? = try {parse(value)} catch (_:IllegalArgumentException) {null}
