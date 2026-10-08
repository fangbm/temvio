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
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek

@Composable
fun AcademicScreen(coordinator: AcademicScreenCoordinator, initialKind: AcademicKind) {
    var kind by remember(initialKind) { mutableStateOf(initialKind) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(coordinator, initialKind) {
        coordinator.selected = null; coordinator.refresh()
        coordinator.requestedCourse?.let { id -> coordinator.selected = coordinator.facts?.courses?.firstOrNull { it.id == id }?.let { AcademicRecord.Subject(it) }; coordinator.requestedCourse = null }
        coordinator.requestedExam?.let { id -> coordinator.selected = coordinator.facts?.exams?.firstOrNull { it.id == id }?.let { AcademicRecord.Assessment(it) }; coordinator.requestedExam = null }
    }
    // Deferred lazy intervals must retain the same frame for item count and measurement.
    val facts = coordinator.facts
    val loading = coordinator.loading
    val saving = coordinator.saving
    val read = coordinator.read
    val technicalError = coordinator.technicalError
    val committedId = coordinator.lastCommittedMutationId
    val currentKind = kind
    val records = facts?.records(currentKind).orEmpty()
    val selected = coordinator.selected?.let { old -> facts?.records(currentKind)?.firstOrNull { it.id == old.id && it.kind == old.kind } }
    AdaptiveDetailWorkspace(selected != null, { coordinator.selected = null }, {
        selected?.let { AcademicDetail(it, coordinator) }
    }) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag(if (loading) "academic-loading" else "academic-ready"),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionHeading(if (initialKind == AcademicKind.EXAM) "Exams" else "Courses & academic setup", "Explicit source facts. Sessions are derived, never authored here.") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AcademicKind.COURSE, AcademicKind.EXAM, AcademicKind.YEAR, AcademicKind.SEMESTER, AcademicKind.TEMPLATE).forEach { choice ->
                        NavigationControl(choice.label, currentKind == choice, { kind = choice; coordinator.selected = null })
                    }
                }
            }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { coordinator.create(currentKind) }, enabled = facts != null && !loading) { Text("New ${currentKind.label}") }
                ActionButton(role = ActionRole.TERTIARY, onClick = { scope.launch { coordinator.refresh() } }, enabled = !loading) { Text("Refresh academic facts") }
            } }
            if (loading) item { StatusMessage("Loading", "Reading authoritative academic source facts…") }
            technicalError?.let { error -> item { StatusMessage("Load error", error) } }
            when (val frameRead = read) {
                is ConflictAwareRead.Unprojectable -> item { StatusMessage("Sync conflict · unavailable", "${frameRead.reason}. Facts withheld; resolve ${frameRead.conflictIds.joinToString()} before authoring.") }
                is ConflictAwareRead.Projected -> if (frameRead.syncConflictRefs.isNotEmpty()) item { StatusMessage("Provisional academic facts", "OPEN Sync conflicts: ${frameRead.syncConflictRefs.joinToString { "${it.entityKind}: ${it.conflictIds.joinToString()}" }}. Ordinary Save cannot resolve them.") }
                null -> Unit
            }
            if (!loading && records.isEmpty() && facts != null) item {
                StatusMessage("No ${currentKind.label} records", when (currentKind) {
                    AcademicKind.COURSE -> "Create an AcademicYear and Semester with explicit weeks, then save a Course. Rules are saved separately."
                    AcademicKind.EXAM -> "Choose a saved Semester. Unscheduled, DateOnly and Exact are distinct choices."
                    else -> "Create explicit setup facts. No default academic graph is generated."
                })
            }
            items(records, key = { "${it.kind}:${it.id}" }) { record ->
                PresentationCard {
                    SectionHeading(record.title, record.kind.label)
                    Text(record.summary(coordinator))
                    ActionButton(role = ActionRole.TERTIARY, onClick = { coordinator.selected = record }, modifier = Modifier.testTag("academic-select-${record.id}")) { Text("View ${record.kind.label}") }
                }
            }
            if (committedId != null) item {
                Text(when {
                    loading || saving -> "Saved · refreshing source facts…"
                    read is ConflictAwareRead.Projected -> "Saved · authoritative source facts loaded"
                    else -> "Saved · source refresh unavailable; retry explicitly."
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Responsive owned detail surface. Platform navigation never stores semantic state here. */
@Composable
fun AdaptiveDetailWorkspace(hasSelection: Boolean, onClose: () -> Unit, detail: @Composable () -> Unit, list: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 760.dp && maxHeight >= 480.dp && LocalDensity.current.fontScale < 1.6f
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { list() }
            if (hasSelection && twoPane) Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(role = ActionRole.TERTIARY, onClick = onClose) { Text("Close detail") }; detail()
            }
        }
        if (hasSelection && !twoPane) AlertDialog(onDismissRequest = onClose,
            title = { Text("Selected detail") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { detail() } },
            confirmButton = { ActionButton(role = ActionRole.TERTIARY, onClick = onClose) { Text("Close detail") } })
    }
}

private fun AcademicRecord.summary(coordinator: AcademicScreenCoordinator): String = when (this) {
    is AcademicRecord.Year -> "${value.startDate} – ${value.endDateExclusive} exclusive"
    is AcademicRecord.Term -> "${value.startDate} – ${value.endDateExclusive} · ${value.timeZone.id} · ${value.academicWeeks.size} explicit weeks"
    is AcademicRecord.Template -> "${value.periods.size} explicit periods · gaps allowed"
    is AcademicRecord.Subject -> "${value.code ?: "No code"} · ${coordinator.facts?.semesters?.firstOrNull { it.id == value.semesterId }?.name ?: value.semesterId.value}"
    is AcademicRecord.Rule -> "${value.timeLabel(coordinator)} · ${value.room ?: "No room"}"
    is AcademicRecord.Assessment -> when (val s = value.schedule) {
        ExamSchedule.Unscheduled -> "Unscheduled · no Calendar placement"
        is ExamSchedule.DateOnly -> "DateOnly · ${s.date} · no invented time"
        is ExamSchedule.Exact -> "Exact · ${s.time.start} – ${s.time.endExclusive} · ${s.time.timeZone.id}"
    }
}

private fun CourseScheduleRule.timeLabel(coordinator: AcademicScreenCoordinator): String = when (val spec = time) {
    is CourseTimeSpec.ClockTime -> {
        val course = coordinator.facts?.courses?.firstOrNull { it.id == courseId }
        val term = coordinator.facts?.semesters?.firstOrNull { it.id == course?.semesterId }
        "ClockTime · ${spec.start} – ${spec.endExclusive} exclusive · ${term?.timeZone?.id ?: "semester zone unavailable"}"
    }
    is CourseTimeSpec.PeriodBased -> {
        val template = coordinator.facts?.periodTemplates?.firstOrNull { it.id == spec.periodTemplateId }
        "PeriodBased · ${template?.name ?: "template unavailable"} · periods ${spec.startPeriod.value} – ${spec.endPeriodInclusive.value} inclusive"
    }
}

@Composable
private fun AcademicDetail(record: AcademicRecord, coordinator: AcademicScreenCoordinator) {
    SectionHeading(record.title, record.kind.label)
    Text(record.summary(coordinator))
    Text("Source ID ${record.id}", style = MaterialTheme.typography.bodySmall)
    ActionButton(role = ActionRole.SECONDARY, onClick = { coordinator.edit(record) }) { Text("Edit ${record.kind.label}") }
    when (record) {
        is AcademicRecord.Subject -> {
            SectionHeading("Schedule rules", "Course and Rule commits are separate.")
            ActionButton(onClick = { coordinator.create(AcademicKind.RULE, record.value.id) }) { Text("New CourseScheduleRule") }
            val rules = coordinator.facts?.courseScheduleRules.orEmpty().filter { it.courseId == record.value.id }
            if (rules.isEmpty()) Text("No rules. Saving this Course did not create a timetable.")
            rules.forEach { rule ->
                Text("${rule.dayOfWeek} · ${rule.teachingWeeks.weeks.joinToString { it.value.toString() }} · ${rule.timeLabel(coordinator)} · ${rule.room ?: "No room"}")
                ActionButton(role = ActionRole.TERTIARY, onClick = { coordinator.edit(AcademicRecord.Rule(rule)) }) { Text("Edit rule ${rule.dayOfWeek}") }
            }
        }
        is AcademicRecord.Term -> record.value.academicWeeks.forEach { Text("Week ${it.number.value}: ${it.startDate} – ${it.endDateExclusive} exclusive") }
        is AcademicRecord.Template -> record.value.periods.forEach { Text("Period ${it.number.value}: ${it.start} – ${it.endExclusive}") }
        else -> Unit
    }
}

@Composable
fun AcademicEditor(coordinator: AcademicScreenCoordinator) {
    // Keep the post-commit refresh alive after the dialog closes. This scope is
    // still owned by the app-mounted editor, never a global/background write path.
    val scope = rememberCoroutineScope()
    val draft = coordinator.draft ?: return
    val facts = coordinator.facts
    var discard by remember(draft.before, draft.kind) { mutableStateOf(false) }
    val dismiss: () -> Unit = { if (!coordinator.saving) { if (coordinator.dirty) discard = true else coordinator.closeDraft() } }
    fun change(value: AcademicDraft) { if (!coordinator.saving) coordinator.draft = value }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text("${if (draft.before == null) "New" else "Edit"} ${draft.kind.label}") },
        text = { Column(Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.kind != AcademicKind.RULE) DraftField(if (draft.kind == AcademicKind.EXAM) "Title" else "Name", draft.name) { change(draft.copy(name = it)) }
            when (draft.kind) {
                AcademicKind.YEAR -> { DraftField("Start date (YYYY-MM-DD)", draft.start) { change(draft.copy(start = it)) }; DraftField("End exclusive date", draft.end) { change(draft.copy(end = it)) } }
                AcademicKind.SEMESTER -> {
                    TypedChoice("AcademicYear", draft.yearId, facts?.academicYears.orEmpty().map { it.id to it.name }) { change(draft.copy(yearId = it)) }
                    if (facts?.academicYears.isNullOrEmpty()) Text("Create an AcademicYear in setup first.")
                    DraftField("Start date (YYYY-MM-DD)", draft.start) { change(draft.copy(start = it)) }
                    DraftField("End exclusive date", draft.end) { change(draft.copy(end = it)) }
                    DraftField("Time zone", draft.zone) { change(draft.copy(zone = it)) }
                    SectionHeading("Explicit AcademicWeeks", "Enter number, start and exclusive end. Exactly seven days; order 1..N. No generated weeks.")
                    draft.weeks.forEachIndexed { index, row ->
                        PresentationCard {
                            DraftField("Week ${index + 1} number", row.number) { change(draft.copy(weeks = draft.weeks.replacing(index, row.copy(number = it)))) }
                            DraftField("Week ${index + 1} start", row.start) { change(draft.copy(weeks = draft.weeks.replacing(index, row.copy(start = it)))) }
                            DraftField("Week ${index + 1} end exclusive", row.end) { change(draft.copy(weeks = draft.weeks.replacing(index, row.copy(end = it)))) }
                            RowControls(index, draft.weeks.size, "week", { change(draft.copy(weeks = draft.weeks.filterIndexed { i, _ -> i != index })) }, { delta -> change(draft.copy(weeks = draft.weeks.moved(index, delta))) })
                        }
                    }
                    ActionButton(role = ActionRole.SECONDARY, onClick = { change(draft.copy(weeks = draft.weeks + WeekDraft())) }) { Text("Add explicit week") }
                }
                AcademicKind.TEMPLATE -> {
                    SectionHeading("Explicit periods", "Ordered numbers and non-overlapping local times. Gaps remain gaps.")
                    draft.periods.forEachIndexed { index, row ->
                        PresentationCard {
                            DraftField("Period ${index + 1} number", row.number) { change(draft.copy(periods = draft.periods.replacing(index, row.copy(number = it)))) }
                            DraftField("Period ${index + 1} start (HH:MM)", row.start) { change(draft.copy(periods = draft.periods.replacing(index, row.copy(start = it)))) }
                            DraftField("Period ${index + 1} end exclusive", row.end) { change(draft.copy(periods = draft.periods.replacing(index, row.copy(end = it)))) }
                            RowControls(index, draft.periods.size, "period", { change(draft.copy(periods = draft.periods.filterIndexed { i, _ -> i != index })) }, { delta -> change(draft.copy(periods = draft.periods.moved(index, delta))) })
                        }
                    }
                    ActionButton(role = ActionRole.SECONDARY, onClick = { change(draft.copy(periods = draft.periods + PeriodDraft())) }) { Text("Add explicit period") }
                }
                AcademicKind.COURSE -> {
                    TypedChoice("Semester", draft.semesterId, facts?.semesters.orEmpty().map { it.id to it.name }) { change(draft.copy(semesterId = it)) }
                    if (facts?.semesters.isNullOrEmpty()) Text("Create a Semester with explicit weeks in setup first.")
                    DraftField("Code (optional)", draft.code) { change(draft.copy(code = it)) }
                    Text("Save commits only the Course. Add a separate Rule after it is saved.")
                }
                AcademicKind.RULE -> {
                    TypedChoice("Course", draft.courseId, facts?.courses.orEmpty().map { it.id to it.name }) { change(draft.copy(courseId = it)) }
                    TypedChoice("Weekday", draft.weekday, DayOfWeek.entries.map { it to it.name }) { change(draft.copy(weekday = it)) }
                    val course = facts?.courses?.firstOrNull { it.id == draft.courseId }
                    val term = facts?.semesters?.firstOrNull { it.id == course?.semesterId }
                    Text("Available academic weeks: ${term?.academicWeeks?.joinToString { it.number.value.toString() } ?: "Choose Course"} · zone ${term?.timeZone?.id ?: "unknown"}")
                    DraftField("Teaching weeks (explicit comma-separated numbers)", draft.teachingWeeks) { change(draft.copy(teachingWeeks = it)) }
                    TypedChoice("Rule time", draft.ruleTime, RuleTimeChoice.entries.map { it to it.name }) { change(draft.copy(ruleTime = it, start = "", end = "", templateId = null)) }
                    if (draft.ruleTime == RuleTimeChoice.PERIOD_BASED) {
                        TypedChoice("PeriodTemplate", draft.templateId, facts?.periodTemplates.orEmpty().map { it.id to it.name }) { change(draft.copy(templateId = it)) }
                        if (facts?.periodTemplates.isNullOrEmpty()) Text("No PeriodTemplate. Cancel and create explicit periods in setup; ClockTime does not require one.")
                    }
                    if (draft.ruleTime != null) {
                        DraftField(if (draft.ruleTime == RuleTimeChoice.CLOCK_TIME) "Local start (HH:MM)" else "Start period", draft.start) { change(draft.copy(start = it)) }
                        DraftField(if (draft.ruleTime == RuleTimeChoice.CLOCK_TIME) "Local end exclusive (HH:MM)" else "End period inclusive", draft.end) { change(draft.copy(end = it)) }
                    }
                    DraftField("Room (optional)", draft.room) { change(draft.copy(room = it)) }
                    Text("This Save is a separate Rule command, not an atomic Course + timetable save.")
                }
                AcademicKind.EXAM -> {
                    TypedChoice("Semester", draft.semesterId, facts?.semesters.orEmpty().map { it.id to it.name }) { change(draft.copy(semesterId = it, courseId = null)) }
                    val courses = facts?.courses.orEmpty().filter { it.semesterId == draft.semesterId }
                    TypedChoice<CourseId?>("Linked Course (optional)", draft.courseId, listOf(null to "None") + courses.map { it.id to it.name }) { change(draft.copy(courseId = it)) }
                    TypedChoice("Exam schedule", draft.examSchedule, ExamScheduleChoice.entries.map { it to it.name }) { change(draft.copy(examSchedule = it, start = "", end = "")) }
                    if (draft.examSchedule == ExamScheduleChoice.DATE_ONLY) DraftField("Exam date (YYYY-MM-DD)", draft.start) { change(draft.copy(start = it)) }
                    if (draft.examSchedule == ExamScheduleChoice.EXACT) {
                        Text("Semester zone: ${facts?.semesters?.firstOrNull { it.id == draft.semesterId }?.timeZone?.id ?: "Choose Semester"}. DST gaps/ambiguity rejected.")
                        DraftField("Exact start (YYYY-MM-DDTHH:MM)", draft.start) { change(draft.copy(start = it)) }
                        DraftField("Exact end exclusive", draft.end) { change(draft.copy(end = it)) }
                    }
                }
            }
            coordinator.feedbackDetail?.let { StatusMessage(coordinator.feedback?.name ?: "Save result", it) }
            if (coordinator.feedback in listOf(AcademicFeedback.STALE, AcademicFeedback.NOT_FOUND, AcademicFeedback.TECHNICAL_ERROR)) {
                ActionButton(role = ActionRole.TERTIARY, onClick = { scope.launch { coordinator.refresh() } }, enabled = !coordinator.loading) { Text("Refresh facts, keep draft") }
                ActionButton(role = ActionRole.SECONDARY, onClick = coordinator::reloadDraftFromFacts, enabled = !coordinator.loading) { Text("Reload source into draft") }
            }
        } },
        confirmButton = { ActionButton(onClick = { scope.launch { coordinator.save() } }, enabled = !coordinator.saving && coordinator.facts != null) { Text(if (coordinator.saving) "Saving…" else "Save") } },
        dismissButton = { ActionButton(role = ActionRole.TERTIARY, onClick = dismiss, enabled = !coordinator.saving) { Text("Cancel") } })
    if (discard) DraftDiscardPrompt({ coordinator.closeDraft(); discard = false }, { discard = false })
}

@Composable
private fun DraftField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 48.dp), label = { Text(label) })
}
@Composable
private fun <T> TypedChoice(label: String, value: T?, choices: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionButton(role = ActionRole.SECONDARY, onClick = { open = true }) { Text("$label: ${choices.firstOrNull { it.first == value }?.second ?: "Choose"}") }
        DropdownMenu(open, { open = false }) { choices.forEach { (id, title) ->
            DropdownMenuItem(text = { Text(title) }, onClick = { onChange(id); open = false }, modifier = Modifier.heightIn(min = 48.dp))
        } }
    }
}
@Composable
private fun RowControls(index: Int, size: Int, label: String, remove: () -> Unit, move: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(role = ActionRole.TERTIARY, onClick = remove) { Text("Remove $label ${index + 1}") }
        ActionButton(role = ActionRole.TERTIARY, onClick = { move(-1) }, enabled = index > 0) { Text("Move $label ${index + 1} up") }
        ActionButton(role = ActionRole.TERTIARY, onClick = { move(1) }, enabled = index + 1 < size) { Text("Move $label ${index + 1} down") }
    }
}
private fun <T> List<T>.replacing(index: Int, value: T): List<T> = mapIndexed { i, old -> if (i == index) value else old }
private fun <T> List<T>.moved(index: Int, delta: Int): List<T> = toMutableList().also { list ->
    if (index + delta in indices) { val value = list.removeAt(index); list.add(index + delta, value) }
}.toList()
