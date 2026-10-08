package dev.agenticscheduler.desktop

import dev.agenticscheduler.ui.SectionHeading
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import dev.agenticscheduler.ui.ActionButton as Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.planner.DogfoodPlannerService
import dev.agenticscheduler.application.planner.PlanBranchApplyResult
import dev.agenticscheduler.application.planner.PlannerPreview
import dev.agenticscheduler.application.planner.PlanningProfileSettingsService
import dev.agenticscheduler.application.planner.PlanningProfileSettingsResult
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.planner.LocalReflowRequest
import dev.agenticscheduler.planner.PlanningHorizon
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration

@Composable
internal fun PlannerDogfoodPanel(
    reads: ConflictAwareSourceFactReadService,
    focusBlocks: List<dev.agenticscheduler.domain.task.FocusBlock>,
    planner: DogfoodPlannerService,
    profileSettings: PlanningProfileSettingsService,
    coordinator: DesktopPlannerScreenCoordinator,
) {
    val profileRead by remember(reads) { reads.observePlanningProfiles() }.collectAsState(ConflictAwareRead.Projected(emptyList<PlanningProfile>().toImmutableList()))
    val profileValues = (profileRead as? ConflictAwareRead.Projected)?.value.orEmpty().toImmutableList()
    var selectedProfileId by coordinator.selectedProfileId
    var createProfile by coordinator.createProfile
    var editingProfile by coordinator.editingProfile
    var horizonStart by coordinator.horizonStart
    var horizonEnd by coordinator.horizonEnd
    var affectedId by coordinator.affectedId
    var preview by coordinator.preview
    var message by coordinator.message
    val scope = checkNotNull(coordinator.scope)
    val selected = profileValues.firstOrNull { it.id == selectedProfileId }
    Column(Modifier.testTag("planner-dogfood")) {
        SectionHeading("Planner", "Preview before applying to Active State")
        Row {
            Button(onClick = { createProfile = true }) { Text("New PlanningProfile") }
            selected?.let { Button(onClick = { editingProfile = it }) { Text("Edit selected profile") } }
        }
        profileValues.forEach { profile -> Button(onClick = { selectedProfileId = profile.id }) { Text(if (profile.id == selectedProfileId) "Selected: ${profile.name}" else profile.name) } }
        if (profileRead is ConflictAwareRead.Unprojectable) Text("Sync conflict source facts require resolution before they can be displayed.")
        if (profileValues.isEmpty()) Text("Create a PlanningProfile, then configure explicit availability before planning.")
        OutlinedTextField(horizonStart, { horizonStart = it }, modifier = Modifier.testTag("planner-horizon-start"), label = { Text("Horizon start Instant (e.g. 2026-09-14T09:00:00Z)") })
        OutlinedTextField(horizonEnd, { horizonEnd = it }, modifier = Modifier.testTag("planner-horizon-end"), label = { Text("Horizon end Instant (exclusive)") })
        Row {
            Button(modifier = Modifier.testTag("planner-full-replan"), onClick = {
                val horizon = parseHorizon(horizonStart, horizonEnd)
                if (selected == null || horizon == null) message = "Select a PlanningProfile and enter an explicit positive horizon."
                else scope.launch { preview = planner.fullReplan(selected.id, Clock.System.now(), horizon); message = null }
            }) { Text("Full Replan preview") }
            Button(onClick = {
                val horizon = parseHorizon(horizonStart, horizonEnd)
                val configured = selected?.configuration as? PlanningProfileConfiguration.Configured
                val affected = runCatching { FocusBlockId(affectedId) }.getOrNull()
                if (selected == null || horizon == null || configured == null || affected == null) message = "Local Reflow requires a configured profile, one FocusBlock ID, and an explicit horizon."
                else scope.launch {
                    preview = planner.localReflow(selected.id, Clock.System.now(), horizon, LocalReflowRequest(persistentListOf(affected), persistentListOf(), ZonedTimeRange(horizon.start, horizon.endExclusive, configured.timeZone)))
                    message = null
                }
            }) { Text("Local Reflow preview") }
        }
        Text("Affected FocusBlock for Local Reflow")
        focusBlocks.forEach { block -> Button(onClick = { affectedId = block.id.value }) { Text(block.id.value) } }
        OutlinedTextField(affectedId, { affectedId = it }, label = { Text("FocusBlock ID") })
        message?.let { Text(it) }
        when (val result = preview) {
            is PlannerPreview.Applicable -> {
                Text("PlanBranch preview: ${result.branch.mutations.size} FocusBlock mutation(s)", modifier = Modifier.testTag("planner-preview"))
                result.branch.mutations.forEach { Text(it.toString()) }
                result.branch.issues.forEach { Text("PlannerIssue: $it") }
                Row {
                    Button(modifier = Modifier.testTag("planner-apply"), onClick = { scope.launch { when (val applied = planner.apply(result.branch, Clock.System.now())) {
                        is PlanBranchApplyResult.Applied -> { message = "PlanBranch applied atomically."; preview = null }
                        is PlanBranchApplyResult.Stale -> { preview = PlannerPreview.Applicable(applied.branch); message = "PlanBranch is stale; preview again before Apply." }
                        is PlanBranchApplyResult.BlockedBySyncConflict -> message = "PlanBranch intersects an unresolved sync conflict. Resolve it before applying."
                    } } }) { Text("Apply PlanBranch") }
                    Button(onClick = { preview = null; message = "PlanBranch cancelled; Active State was unchanged." }) { Text("Cancel preview") }
                }
            }
            is PlannerPreview.Infeasible -> result.issues.forEach { Text("PlannerIssue / infeasible: $it") }
            is PlannerPreview.InvalidInput -> result.issues.forEach { Text("PlannerIssue / invalid input: $it") }
            null -> Unit
        }
    }
    if (createProfile) PlanningProfileDialog(null, profileSettings, { createProfile = false }, { createProfile = false })
    editingProfile?.let { PlanningProfileDialog(it, profileSettings, { editingProfile = null }, { editingProfile = null }) }
}

@Composable
internal fun PlanningProfileDialog(existing: PlanningProfile?, settings: PlanningProfileSettingsService, onSaved: () -> Unit, onDismiss: () -> Unit) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    val configured = existing?.configuration as? PlanningProfileConfiguration.Configured
    var timeZone by remember(existing) { mutableStateOf(configured?.timeZone?.id ?: "") }
    var minimum by remember(existing) { mutableStateOf(configured?.minimumFocusBlock?.toString() ?: "") }
    var preferred by remember(existing) { mutableStateOf(configured?.preferredFocusBlock?.toString() ?: "") }
    var maximum by remember(existing) { mutableStateOf(configured?.maximumFocusBlock?.toString() ?: "") }
    var windows by remember(existing) { mutableStateOf(configured?.weeklyAvailability?.joinToString("\n") { "${it.dayOfWeek} ${it.start}-${it.endExclusive}" } ?: "") }
    var allDayPolicy by remember(existing) { mutableStateOf(configured?.allDayEventPolicy) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val handleSaveResult: (PlanningProfileSettingsResult) -> Unit = { result ->
        when (result) {
            is PlanningProfileSettingsResult.Success -> onSaved()
            PlanningProfileSettingsResult.Stale -> error = "Profile changed since this editor opened. Draft retained; close and reopen explicitly to reload."
            PlanningProfileSettingsResult.NotFound -> error = "Profile no longer exists. Draft retained."
            is PlanningProfileSettingsResult.BlockedBySyncConflict -> error = "Profile intersects an unresolved sync conflict. Draft retained; resolve before saving."
        }
    }
    val initialDraft = remember(existing) { listOf(name, timeZone, minimum, preferred, maximum, windows, allDayPolicy) }
    var discardRequested by remember { mutableStateOf(false) }
    val requestDismiss: () -> Unit = { if (listOf(name, timeZone, minimum, preferred, maximum, windows, allDayPolicy) != initialDraft) discardRequested = true else onDismiss() }
    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (existing == null) "New PlanningProfile" else "PlanningProfile settings") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text("Profile name") })
            if (existing != null) {
                OutlinedTextField(timeZone, { timeZone = it }, label = { Text("Time zone, e.g. Asia/Shanghai") })
                OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum FocusBlock duration") })
                OutlinedTextField(preferred, { preferred = it }, label = { Text("Preferred FocusBlock duration") })
                OutlinedTextField(maximum, { maximum = it }, label = { Text("Maximum FocusBlock duration") })
                OutlinedTextField(windows, { windows = it }, label = { Text("Availability: MONDAY 09:00-17:00 per line") })
                Button(onClick = { allDayPolicy = when (allDayPolicy) { null -> AllDayEventPolicy.NON_BLOCKING; AllDayEventPolicy.NON_BLOCKING -> AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY; AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY -> null } }) { Text("All-day Event policy: ${allDayPolicy ?: "Choose explicitly"}") }
            } else Text("New profiles start deliberately Unconfigured; configure it after creation.")
            error?.let { Text(it) }
        } },
        confirmButton = { Button(onClick = {
            if (existing == null) scope.launch { runCatching { settings.createUnconfigured(name) }.onSuccess(handleSaveResult).onFailure { error = it.message } }
            else {
                val configuration = parseConfiguration(timeZone, minimum, preferred, maximum, windows, allDayPolicy)
                if (configuration == null) error = "Enter a valid timezone, ordered positive durations, non-overlapping availability, and choose an all-day policy."
                else scope.launch { runCatching { settings.save(existing.copy(name = name, configuration = configuration), expectedBefore = existing) }.onSuccess(handleSaveResult).onFailure { error = it.message } }
            }
        }) { Text(if (existing == null) "Create" else "Save") } },
        dismissButton = { Button(onClick = requestDismiss) { Text("Cancel") } },
    )
    if (discardRequested) dev.agenticscheduler.ui.DraftDiscardPrompt(
        onDiscard = onDismiss, onKeepEditing = { discardRequested = false })
}


internal fun parseHorizon(start: String, end: String): PlanningHorizon? = runCatching { PlanningHorizon(kotlin.time.Instant.parse(start), kotlin.time.Instant.parse(end)) }.getOrNull()
internal fun parseConfiguration(zone: String, minimum: String, preferred: String, maximum: String, windows: String, policy: AllDayEventPolicy?): PlanningProfileConfiguration.Configured? = runCatching {
    val parsedWindows = windows.lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        val times = parts[1].split("-", limit = 2)
        WeeklyAvailabilityWindow(DayOfWeek.valueOf(parts[0].uppercase()), LocalTime.parse(times[0]), LocalTime.parse(times[1]))
    }.toImmutableList()
    PlanningProfileConfiguration.Configured(TimeZone.of(zone), parsedWindows, Duration.parse(minimum), Duration.parse(preferred), Duration.parse(maximum), requireNotNull(policy))
}.getOrNull()
