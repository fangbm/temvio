package dev.agenticscheduler.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.agenticscheduler.application.history.UndoCapability
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(c: HistoryScreenCoordinator,scope:CoroutineScope) {
    LaunchedEffect(c) {c.refresh()}
    val detail = c.detail
    val rows = c.rows
    val loaded = c.loaded
    val message = c.message
    FeatureListDetail(detail!=null,c::closeDetail,list={
        LazyColumn(Modifier.fillMaxSize().padding(20.dp).testTag("history-timeline"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {SectionHeading("History", "Authoritative committed changes · Undo is new compensation")}
            item {ActionButton({scope.launch {c.refresh()}},enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Refresh History")}; message?.let {StatusMessage("History result",it)} }
            if(!loaded) item {Text("Loading History…")}
            else if(rows.isEmpty()) item {Text("No committed changes.")}
            items(rows,key={it.operation.mutationId}) {row ->
                Surface(color=LocalTemvioColors.current.container,shape=MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(row.operation.origin.originLabel(),style=MaterialTheme.typography.titleMedium)
                        Text(row.operation.orderedMutations.map {it.entityKind.name.lowercase().replace('_',' ')}.distinct().joinToString(" · "))
                        Text("${row.operation.orderedMutations.size} change(s) · recorded ${kotlin.time.Instant.fromEpochMilliseconds(row.committedAtEpochMillis)}",style=MaterialTheme.typography.bodySmall)
                        ActionButton({scope.launch {c.select(row.operation.mutationId)}},enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Inspect change")}
                    }
                }
            }
            item {ActionButton({scope.launch {c.loadMore()}},enabled=!c.busy && !c.exhausted,role=ActionRole.SECONDARY) {Text("Load more History")}; Text("Bounded Application cursor pages. Recorded time is informational, not causal authority.",style=MaterialTheme.typography.bodySmall) }
        }
    },detail={ detail?.let {d ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("history-detail"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeading("Mutation detail",d.mutation.operation.origin.originLabel())
            Text("One logical mutation · ${d.changes.size} ordered change(s)")
            d.mutation.operation.orderedMutations.forEach {
                Text(it.historySummary()); Text(it.historyDiffSummary(),style=MaterialTheme.typography.bodySmall)
            }
            d.changes.forEach {change ->
                Text("Change ${change.ordinal} · ${change.entityKind} · ${change.operationKind}",style=MaterialTheme.typography.titleSmall)
                Text("Before · ${if(change.beforeImageJson==null) "Absent (creation)" else "Retained semantic image"}")
                Text("After · ${if(change.afterImageJson==null) "Absent (deletion)" else "Retained semantic image"}")
                var technical by remember(d.mutation.operation.mutationId,change.ordinal) {mutableStateOf(false)}
                ActionButton({technical=!technical},role=ActionRole.TERTIARY) {Text(if(technical) "Hide image details ${change.ordinal}" else "Image details ${change.ordinal}")}
                if(technical) {Text("Entity · ${change.entityId}");Text("Before image · ${change.beforeImageJson ?: "none"}");Text("After image · ${change.afterImageJson ?: "none"}")}
                ActionButton({scope.launch {c.inspectEntity(change.entityKind,change.entityId)}},enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Entity change history ${change.ordinal}")}
            }
            if(c.entityChanges.isNotEmpty()) {
                Text("Entity history · bounded page of ${c.entityChanges.size} changes")
                c.entityChanges.forEach {change -> Text("${change.operationKind} · ${change.mutationId} · ordered child ${change.ordinal}",style=MaterialTheme.typography.bodySmall)}
                Text("This is a bounded first page, not a claim of complete entity history.",style=MaterialTheme.typography.bodySmall)
            }
            when(val capability=d.undo) {
                UndoCapability.Available -> {Text("Undo available for this complete mutation group. Current-state preconditions are rechecked atomically.");ActionButton({c.confirmUndo=true},enabled=!c.busy,role=ActionRole.SECONDARY,modifier=Modifier.testTag("history-undo")) {Text("Undo this change")}}
                is UndoCapability.Unsupported -> Text("Undo unsupported · ${capability.reason}")
                UndoCapability.NotFound -> Text("Mutation not found for Undo.")
            }
            var technical by remember(d.mutation.operation.mutationId) {mutableStateOf(false)}
            ActionButton({technical=!technical},role=ActionRole.TERTIARY) {Text(if(technical) "Hide technical metadata" else "Technical metadata")}
            if(technical) {Text("Origin reference · ${d.mutation.operation.origin}");Text("MutationId · ${d.mutation.operation.mutationId}");Text("HLC (diagnostic order) · ${d.mutation.operation.hlc}");Text("DVV (causality) · ${d.mutation.operation.dvv}")}
            ActionButton(c::closeDetail,enabled=!c.busy,role=ActionRole.TERTIARY) {Text("Close mutation detail")}
        }
    } })
    if(c.confirmUndo) AlertDialog(onDismissRequest={if(!c.busy)c.confirmUndo=false},title={Text("Undo this mutation group?")},text={Text("Creates one new compensating mutation. Original History stays intact; all children succeed or none do.")},
        confirmButton={ActionButton({scope.launch {c.undoSelected()}},enabled=!c.busy) {Text("Confirm compensating Undo")}},dismissButton={ActionButton({c.confirmUndo=false},role=ActionRole.TERTIARY,enabled=!c.busy) {Text("Keep change")}})
}

/** App presentation detail: content width/height/font determine readable pane versus modal. */
@Composable
internal fun FeatureListDetail(selected:Boolean,onClose:()->Unit,list:@Composable ()->Unit,detail:@Composable ()->Unit) {
    val font=LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pane=maxWidth>=760.dp && maxHeight>=480.dp && font<1.6f
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {list()}
            if(selected && pane) Box(Modifier.width(360.dp).fillMaxHeight()) {detail()}
        }
        if(selected && !pane) Dialog(onDismissRequest=onClose) {Surface(Modifier.widthIn(max=600.dp).fillMaxHeight(0.9f),shape=MaterialTheme.shapes.large) {detail()} }
    }
}
