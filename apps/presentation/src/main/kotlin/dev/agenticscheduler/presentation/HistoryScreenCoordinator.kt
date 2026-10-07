package dev.agenticscheduler.presentation

import androidx.compose.runtime.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.CancellationException

data class HistoryDetail(val mutation: CommittedMutation, val changes: List<HistoryChange>, val undo: UndoCapability)

/** Application cursor and compensation semantics remain authoritative; no local history store. */
class HistoryScreenCoordinator(private val queries: HistoryQueryService, private val undo: UndoService) {
    var rows by mutableStateOf<List<CommittedMutation>>(emptyList()); private set
    var detail by mutableStateOf<HistoryDetail?>(null); private set
    var entityChanges by mutableStateOf<List<HistoryChange>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var exhausted by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var confirmUndo by mutableStateOf(false)
    private var cursor: HistoryQueryService.TimelineCursor? = null

    suspend fun refresh() = command { loadFirstPage() }
    private suspend fun loadFirstPage() {
        val first = queries.timeline()
        rows = first; cursor = first.lastOrNull()?.cursor(); exhausted = first.isEmpty(); loaded = true
    }
    suspend fun loadMore() = command {
        if (exhausted) return@command
        val page = queries.timeline(cursor)
        rows = (rows + page).distinctBy { it.operation.mutationId }
        cursor = page.lastOrNull()?.cursor() ?: cursor; exhausted = page.isEmpty()
    }
    suspend fun select(mutationId: String) = command {
        val mutation = queries.getMutation(mutationId)
        detail = mutation?.let { HistoryDetail(it,queries.getDiff(mutationId),undo.canUndo(mutationId)) }
        entityChanges = emptyList(); confirmUndo = false
        if (mutation == null) message = "Mutation not found. History was not changed."
    }
    suspend fun inspectEntity(kind: EntityKind, id: String) = command { entityChanges = queries.getEntityChanges(kind,id) }
    fun closeDetail() { if (!busy) { detail = null; entityChanges = emptyList(); confirmUndo = false } }
    suspend fun undoSelected() = command {
        val selected = detail ?: return@command
        if (!confirmUndo || selected.undo != UndoCapability.Available) return@command
        confirmUndo = false
        message = when (val result = undo.undo(selected.mutation.operation.mutationId)) {
            is UndoResult.Applied -> "Compensating mutation committed: ${result.mutationId.value}. Original History is retained."
            is UndoResult.Unsupported -> "Undo unsupported: ${result.reason}"
            is UndoResult.Conflict -> "Undo conflict: current facts changed. No child of the group was undone."
            is UndoResult.BlockedBySyncConflict -> "Undo blocked by open business sync conflict. No changes applied."
            UndoResult.NotFound -> "Mutation not found. No changes applied."
        }
        loadFirstPage()
        val original = queries.getMutation(selected.mutation.operation.mutationId)
        detail = original?.let { HistoryDetail(it,queries.getDiff(it.operation.mutationId),undo.canUndo(it.operation.mutationId)) }
    }
    private suspend fun command(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "History operation could not finish. No success is claimed; refresh before retry." }
        finally { busy = false }
    }
}

private fun CommittedMutation.cursor() = operation.hlc.let { HistoryQueryService.TimelineCursor(it.physicalMillis,it.logical,it.replicaId,operation.mutationId) }
fun MutationOrigin.originLabel(): String = when (this) {
    MutationOrigin.User -> "User"
    MutationOrigin.Planner -> "Planner"
    MutationOrigin.System -> "System"
    is MutationOrigin.Undo -> "Undo (compensation)"
    is MutationOrigin.Agent -> "Agent action"
    is MutationOrigin.ConflictResolution -> "Business conflict resolution"
}

fun EntityMutation.historySummary(): String = when (this) {
    is EventPut -> "Event · ${after.title} · ${if (before == null) "Create" else "Update"}"
    is TaskPut -> "Task · ${after.title} · ${if (before == null) "Create" else "Update"}"
    is PlanningProfilePut -> "PlanningProfile · ${after.name} · ${if (before == null) "Create" else "Update"}"
    is FocusBlockPut -> "FocusBlock · ${if (before == null) "Create" else "Move / Resize / Put"} · ${after.time.start} → ${after.time.endExclusive}"
    is FocusBlockDelete -> "FocusBlock · Delete · ${before.time.start} → ${before.time.endExclusive}"
    is WorkLogAppend -> "WorkLog · Append"
    is TaskDependencyPut -> "TaskDependency · Put"
    is AcademicYearPut -> "AcademicYear · ${after.name}"
    is SemesterPut -> "Semester · ${after.name}"
    is CoursePut -> "Course · ${after.name}"
    is PeriodTemplatePut -> "PeriodTemplate · ${after.name}"
    is AcademicHolidayPut -> "AcademicHoliday · Put"
    is CourseScheduleRulePut -> "CourseScheduleRule · Put"
    is CourseOccurrenceExceptionPut -> "CourseOccurrenceException · Put"
    is ExamPut -> "Exam · ${after.title}"
}

/** Readable values come from the retained typed operation, never decoded persistence JSON. */
fun EntityMutation.historyDiffSummary(): String = when(this) {
    is TaskPut -> "Before: ${before?.readable() ?: "absent"}\nAfter: ${after.readable()}"
    is EventPut -> "Before: ${before?.readable() ?: "absent"}\nAfter: ${after.readable()}"
    is FocusBlockPut -> "Before: ${before?.time?.readable() ?: "absent"}\nAfter: ${after.time.readable()}"
    is FocusBlockDelete -> "Before: ${before.time.readable()}\nAfter: absent"
    is PlanningProfilePut -> "Before: ${before?.readable() ?: "absent"}\nAfter: ${after.readable()}"
    else -> "Authoritative ${entityKind.name.lowercase().replace('_',' ')} change. Original structured images remain available in technical detail."
}
private fun ZonedTimeRangeImage.readable() = "Zoned · $start → $endExclusive · $timeZone"
private fun EventImage.readable(): String {
    val placement=when(val v=time) {
        is EventTimeImage.Zoned -> v.time.readable()
        is EventTimeImage.AllDay -> "AllDay · ${v.dates.startDate} → ${v.dates.endDateExclusive} (exclusive)"
        is EventTimeImage.Floating -> "Floating · ${v.time.start} → ${v.time.endExclusive} (no timezone)"
    }
    return "$title; $placement; $flexibility; $pinState"
}
private fun TaskImage.readable(): String {
    val due=deadline?.let {v ->
        val type=when(val d=v.deadline) {
            is DeadlineImage.Exact -> "Exact · ${d.at} · ${d.timeZone}"
            is DeadlineImage.DateOnly -> "DateOnly · ${d.date}"
        }
        "$type; ${v.policy}; overflow ${v.overflowPolicy}"
    } ?: "none"
    return "$title; $status; $priority; estimated ${effort.estimated ?: "unknown"}, completed ${effort.completed}, remaining ${effort.remaining ?: "unknown"}; deadline $due"
}
private fun PlanningProfileImage.readable(): String = when(val c=configuration) {
    PlanningProfileConfigurationImage.Unconfigured -> "$name; Unconfigured"
    is PlanningProfileConfigurationImage.Configured -> "$name; ${c.timeZone}; min ${c.minimumFocusDuration}, preferred ${c.preferredFocusDuration}, max ${c.maximumFocusDuration}; AllDay ${c.allDayEventPolicy}; ${c.weeklyAvailability.joinToString { "${it.dayOfWeek} ${it.start}–${it.endExclusive}" }}"
}
