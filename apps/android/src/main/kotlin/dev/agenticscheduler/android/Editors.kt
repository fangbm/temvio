package dev.agenticscheduler.android

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import dev.agenticscheduler.ui.ActionButton as Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.agenticscheduler.application.editing.CreateEventInput
import dev.agenticscheduler.application.editing.CreateTaskInput
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.EventEditingService
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.editing.UpdateEventInput
import dev.agenticscheduler.application.editing.UpdateTaskInput
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration

@Composable
internal fun EventEditorDialog(existing: Event?, selectedDate: LocalDate, displayTimeZone: TimeZone, editor: EventEditingService, onSaved: () -> Unit, onDismiss: () -> Unit, onReload: (suspend () -> Boolean)? = null) {
    var kind by remember(existing) { mutableStateOf(existing.eventKind()) }
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var start by remember(existing, selectedDate) { mutableStateOf(existing.startText(selectedDate)) }
    var end by remember(existing, selectedDate) { mutableStateOf(existing.endText(selectedDate)) }
    var timeZone by remember(existing, displayTimeZone) { mutableStateOf(existing.timeZoneText(displayTimeZone)) }
    var flexibility by remember(existing) { mutableStateOf(existing?.flexibility ?: Flexibility.HARD) }
    var pinState by remember(existing) { mutableStateOf(existing?.pinState ?: PinState.UNPINNED) }
    var error by remember(existing) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var stale by remember(existing) { mutableStateOf(false) }
    val initialDraft = remember(existing) { listOf(kind, title, start, end, timeZone, flexibility, pinState) }
    var discardRequested by remember { mutableStateOf(false) }
    val requestDismiss: () -> Unit = { if (saving) Unit else if (listOf(kind, title, start, end, timeZone, flexibility, pinState) != initialDraft) discardRequested = true else onDismiss() }
    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (existing == null) "New Event" else "Edit Event") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") })
                Button(onClick = { kind = kind.next() }) { Text("Time kind: $kind") }
                OutlinedTextField(start, { start = it }, label = { Text(if (kind == EventKind.ALL_DAY) "Start date (YYYY-MM-DD)" else "Start (YYYY-MM-DDTHH:MM)") })
                OutlinedTextField(end, { end = it }, label = { Text(if (kind == EventKind.ALL_DAY) "End exclusive date" else "End exclusive") })
                if (kind == EventKind.ZONED) OutlinedTextField(timeZone, { timeZone = it }, label = { Text("Time zone") })
                Button(onClick = { flexibility = flexibility.next() }) { Text("Flexibility: $flexibility") }
                Button(onClick = { pinState = pinState.next() }) { Text("Pin state: $pinState") }
                error?.let { Text(it) }
                if (stale && onReload != null) Button(role = dev.agenticscheduler.ui.ActionRole.SECONDARY, enabled = !saving, onClick = {
                    scope.launch { if (!onReload()) error = "Source missing, conflicted or unavailable. Draft retained; no raw fallback." }
                }) { Text("Reload source (discard draft)") }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                val time = parseEventTime(kind, start, end, timeZone)
                if (time == null) error = "Enter a valid time range." else scope.launch {
                    if (saving) return@launch
                    saving = true
                    try {
                        when (val result = if (existing == null) editor.create(CreateEventInput(title, time, flexibility, pinState)) else editor.update(UpdateEventInput(existing.id, title, time, flexibility, pinState), expectedBefore = existing)) {
                            is EditingResult.Success -> onSaved()
                            is EditingResult.Invalid -> error = result.issues.joinToString()
                            EditingResult.NotFound -> error = "Event no longer exists."
                            EditingResult.Stale -> { stale = true; error = "Event changed. Draft retained. Explicitly reload before saving." }
                            is EditingResult.BlockedBySyncConflict -> error = "This change intersects an unresolved sync conflict. Resolve it before editing."
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Save failed. Refresh durable facts before retrying; no success is claimed." }
                    finally { saving = false }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { Button(onClick = requestDismiss) { Text("Cancel") } },
    )
    if (discardRequested) dev.agenticscheduler.ui.DraftDiscardPrompt(
        onDiscard = onDismiss, onKeepEditing = { discardRequested = false })
}


@Composable
internal fun TaskEditorDialog(existing: Task?, editor: TaskEditingService, onSaved: () -> Unit, onDismiss: () -> Unit, onReload: (suspend () -> Boolean)? = null) {
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var status by remember(existing) { mutableStateOf(existing?.status ?: TaskStatus.OPEN) }
    var priority by remember(existing) { mutableStateOf(existing?.priority ?: TaskPriority.NORMAL) }
    var estimated by remember(existing) { mutableStateOf(existing?.effort?.estimated?.toString() ?: "") }
    var completed by remember(existing) { mutableStateOf(existing?.effort?.completed?.toString() ?: Duration.ZERO.toString()) }
    var remaining by remember(existing) { mutableStateOf(existing?.effort?.remaining?.toString() ?: "") }
    var deadlineEnabled by remember(existing) { mutableStateOf(existing?.deadline != null) }
    var deadlineKind by remember(existing) { mutableStateOf(existing.deadlineKind()) }
    var deadlineValue by remember(existing) { mutableStateOf(existing.deadlineValue()) }
    var deadlineZone by remember(existing) { mutableStateOf(existing.deadlineZone()) }
    var deadlinePolicy by remember(existing) { mutableStateOf(existing?.deadline?.policy ?: DeadlinePolicy.NORMAL) }
    var overflowPolicy by remember(existing) { mutableStateOf(existing?.deadline?.overflowPolicy ?: OverflowPolicy.ASK) }
    var error by remember(existing) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var stale by remember(existing) { mutableStateOf(false) }
    val initialDraft = remember(existing) { listOf(title, status, priority, estimated, completed, remaining, deadlineEnabled, deadlineKind, deadlineValue, deadlineZone, deadlinePolicy, overflowPolicy) }
    var discardRequested by remember { mutableStateOf(false) }
    val requestDismiss: () -> Unit = { if (saving) Unit else if (listOf(title, status, priority, estimated, completed, remaining, deadlineEnabled, deadlineKind, deadlineValue, deadlineZone, deadlinePolicy, overflowPolicy) != initialDraft) discardRequested = true else onDismiss() }
    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (existing == null) "New Task" else "Edit Task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") })
                if (existing != null) Button(onClick = { status = status.next() }) { Text("Status: $status") } else Text("Status: OPEN")
                Button(onClick = { priority = priority.next() }) { Text("Priority: $priority") }
                OutlinedTextField(estimated, { estimated = it }, label = { Text("Estimated effort (optional, e.g. 1h)") })
                if (existing != null) OutlinedTextField(completed, { completed = it }, label = { Text("Completed effort") }) else Text("Completed effort: 0s")
                OutlinedTextField(remaining, { remaining = it }, label = { Text("Remaining effort (optional)") })
                Button(onClick = { deadlineEnabled = !deadlineEnabled }) { Text(if (deadlineEnabled) "Deadline enabled" else "Add deadline") }
                if (deadlineEnabled) {
                    Button(onClick = { deadlineKind = deadlineKind?.next() ?: DeadlineKind.DATE_ONLY }) { Text("Deadline kind: ${deadlineKind ?: "Choose"}") }
                    OutlinedTextField(deadlineValue, { deadlineValue = it }, label = { Text(if (deadlineKind == DeadlineKind.EXACT) "Deadline (YYYY-MM-DDTHH:MM)" else "Deadline date (YYYY-MM-DD)") })
                    if (deadlineKind == DeadlineKind.EXACT) OutlinedTextField(deadlineZone, { deadlineZone = it }, label = { Text("Time zone") })
                    Button(onClick = { deadlinePolicy = deadlinePolicy.next() }) { Text("Deadline policy: $deadlinePolicy") }
                    Button(onClick = { overflowPolicy = overflowPolicy.next() }) { Text("Overflow policy: $overflowPolicy") }
                }
                error?.let { Text(it) }
                if (stale && onReload != null) Button(role = dev.agenticscheduler.ui.ActionRole.SECONDARY, enabled = !saving, onClick = {
                    scope.launch { if (!onReload()) error = "Source missing, conflicted or unavailable. Draft retained; no raw fallback." }
                }) { Text("Reload source (discard draft)") }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                val parsedEstimated = parseOptionalDuration(estimated)
                val parsedRemaining = parseOptionalDuration(remaining)
                val parsedCompleted = if (existing == null) Duration.ZERO else parseRequiredDuration(completed)
                val deadline = parseDeadline(deadlineEnabled, deadlineKind, deadlineValue, deadlineZone, deadlinePolicy, overflowPolicy)
                if (parsedEstimated == null && estimated.isNotBlank() || parsedRemaining == null && remaining.isNotBlank() || parsedCompleted == null || deadlineEnabled && deadline == null) error = "Enter valid effort and deadline values." else scope.launch {
                    if (saving) return@launch
                    saving = true
                    try {
                        when (val result = if (existing == null) editor.create(CreateTaskInput(title, priority, parsedEstimated, parsedRemaining, deadline)) else editor.update(UpdateTaskInput(existing.id, title, status, priority, parsedEstimated, checkNotNull(parsedCompleted), parsedRemaining, deadline), expectedBefore = existing)) {
                            is EditingResult.Success -> onSaved()
                            is EditingResult.Invalid -> error = result.issues.joinToString()
                            EditingResult.NotFound -> error = "Task no longer exists."
                            EditingResult.Stale -> { stale = true; error = "Task changed. Draft retained. Explicitly reload before saving." }
                            is EditingResult.BlockedBySyncConflict -> error = "This change intersects an unresolved sync conflict. Resolve it before editing."
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Save failed. Refresh durable facts before retrying; no success is claimed." }
                    finally { saving = false }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { Button(onClick = requestDismiss) { Text("Cancel") } },
    )
    if (discardRequested) dev.agenticscheduler.ui.DraftDiscardPrompt(
        onDiscard = onDismiss, onKeepEditing = { discardRequested = false })
}
