package dev.agenticscheduler.wear.presentation

import androidx.compose.runtime.*
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.domain.task.Task
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlin.time.Clock

@Composable
fun WearTodayRoute(service: ConflictAwareSourceFactReadService, syncFailure: String?, enabled: Boolean,
    onAgent: () -> Unit, onStatus: () -> Unit, onRetrySync: () -> Unit) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(zone).date
    val viewport = remember(today, zone) { CalendarViewport(today, today.plus(7, DateTimeUnit.DAY), zone) }
    var failure by remember { mutableStateOf(false) }
    val projection by remember(service, viewport) { service.observe(viewport).catch {
        if (it is CancellationException) throw it
        failure = true
    } }.collectAsState(null)
    val tasks by remember(service) { service.observeTasks().catch {
        if (it is CancellationException) throw it
        failure = true
    } }.collectAsState(null)
    val result = projection ?: CalendarProjectionResult(emptyList<CalendarItem>().toImmutableList(),
        emptyList<CalendarConflict>().toImmutableList(), emptyList<CalendarProjectionIssue>().toImmutableList())
    val conflicts = readConflictIds(tasks)
    WearTodayScreen(todayModel(today, zone, result, conflicts), syncFailure, enabled, onAgent, onStatus, onRetrySync,
        loading = projection == null && !failure, readFailed = failure)
}

@Composable
fun WearTodayScreen(model: WearTodayModel, syncFailure: String?, enabled: Boolean,
    onAgent: () -> Unit, onStatus: () -> Unit, onRetrySync: () -> Unit, loading: Boolean = false, readFailed: Boolean = false) {
    WearPage("wear-today") {
        item { WearHeading("Today") }
        item { WearCopy("${model.today} · Next") }
        model.rows.firstOrNull()?.let { row ->
            item { WearCopy("${row.kind} · ${row.title}") }
            item { WearCopy(row.time) }
        }
        item { WearAction(if (enabled) "Agent" else "Agent setup", "today-agent", primary = true, onClick = onAgent) }
        if (syncFailure != null) {
            item { WearHeading("Sync stopped") }
            item { WearCopy("Automatic sync needs attention. $syncFailure") }
            item { WearAction("Retry sync", "sync-retry", onClick = onRetrySync) }
        }
        if (model.syncConflictCount > 0) item { WearCopy("Business Sync conflict · ${model.syncConflictCount}. Resolve on a larger device.") }
        if (model.overlapCount > 0) item { WearCopy("Calendar overlap · ${model.overlapCount}") }
        if (model.issueCount > 0) item { WearCopy("Some schedule details could not be projected · ${model.issueCount}") }
        if (readFailed) item { WearCopy("Schedule unavailable. Previously displayed facts may be stale.") }
        item { WearHeading("Upcoming") }
        if (model.rows.isEmpty()) item { WearCopy(if (readFailed || model.issueCount > 0) "No reliable schedule loaded" else if (loading) "Loading local schedule…" else "Nothing scheduled in this seven-day window") }
        model.rows.drop(1).forEach { row ->
            item { WearHeading("${row.kind} · ${row.title}") }
            item { WearCopy(row.time) }
        }
        if (model.moreCount > 0) item { WearCopy("${model.moreCount} more upcoming in this window") }
        item { WearAction("Status / setup", "open-status", onClick = onStatus) }
    }
}
