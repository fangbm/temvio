package dev.agenticscheduler.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.ui.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek

/** Root-hosted, shared by Planner and Settings. Navigation/recomposition never invokes Save. */
@Composable
fun PlanningProfileEditorHost(c: PlannerWorkspaceCoordinator, scope: CoroutineScope) {
    val session = c.profileEditor ?: return
    val draft = session.draft
    val busy = c.busy
    fun change(next: PlanningProfileDraft) = c.updateProfileDraft(next)
    AlertDialog(onDismissRequest = c::requestCloseProfileEditor,
        modifier = Modifier.testTag("planning-profile-editor"),
        title = { Text("Edit planning profile") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("profile-editor-fields"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("Profile", "Changes affect future planning only")
                RequestField("Profile name", draft.name, busy, "profile-edit-name") { change(draft.copy(name = it)) }
                Text(if (draft.configured) "Configured" else "Unconfigured · no scheduling defaults")
                if (!draft.configured) ActionButton({ change(draft.copy(configured = true)) }, enabled = !busy,
                    role = ActionRole.SECONDARY, modifier = Modifier.testTag("profile-configure")) { Text("Configure explicitly") }
                if (draft.configured) {
                    SectionHeading("Time zone", "Explicit zone for availability and all-day policy")
                    RequestField("Time zone ID", draft.timeZone, busy, "profile-edit-zone") { change(draft.copy(timeZone = it)) }
                    SectionHeading("Availability", "Local weekday and half-open time range; an empty list means no availability")
                    draft.availability.forEachIndexed { index, row ->
                        PresentationCard {
                            Text("Window ${index + 1}", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DayOfWeek.entries.forEach { day ->
                                    NavigationControl(day.name.lowercase().replaceFirstChar { it.uppercase() }, row.day == day, {
                                        if (!busy) change(draft.copy(availability = draft.availability.mapIndexed { i, v -> if (i == index) v.copy(day = day) else v }.toImmutableList()))
                                    })
                                }
                            }
                            RequestField("Start local time", row.start, busy, "profile-window-$index-start") {
                                change(draft.copy(availability = draft.availability.mapIndexed { i, v -> if (i == index) v.copy(start = it) else v }.toImmutableList()))
                            }
                            RequestField("End local time (exclusive)", row.endExclusive, busy, "profile-window-$index-end") {
                                change(draft.copy(availability = draft.availability.mapIndexed { i, v -> if (i == index) v.copy(endExclusive = it) else v }.toImmutableList()))
                            }
                            ActionButton({ change(draft.copy(availability = draft.availability.filterIndexed { i, _ -> i != index }.toImmutableList())) },
                                enabled = !busy, role = ActionRole.TERTIARY) { Text("Remove window ${index + 1}") }
                        }
                    }
                    ActionButton({ change(draft.copy(availability = (draft.availability + AvailabilityRowDraft()).toImmutableList())) },
                        enabled = !busy, role = ActionRole.SECONDARY, modifier = Modifier.testTag("profile-add-window")) { Text("Add availability window") }
                    SectionHeading("Focus block durations", "Explicit units, for example 25m or 1h; minimum <= preferred <= maximum")
                    RequestField("Minimum duration", draft.minimum, busy, "profile-edit-minimum") { change(draft.copy(minimum = it)) }
                    RequestField("Preferred duration", draft.preferred, busy, "profile-edit-preferred") { change(draft.copy(preferred = it)) }
                    RequestField("Maximum duration", draft.maximum, busy, "profile-edit-maximum") { change(draft.copy(maximum = it)) }
                    SectionHeading("All-day Event policy", "Choose how all-day Events affect automatic scheduling")
                    AllDayEventPolicy.entries.forEach { policy ->
                        NavigationControl(allDayPolicyLabel(policy), draft.allDayPolicy == policy, {
                            if (!busy) change(draft.copy(allDayPolicy = policy))
                        }, Modifier.fillMaxWidth())
                    }
                }
                c.profileFeedback?.let { StatusMessage("Profile result", it) }
                c.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ActionButton(c::requestReloadProfileFromUser, enabled = !busy, role = ActionRole.TERTIARY,
                    modifier = Modifier.testTag("profile-reload")) { Text("Reload current profile") }
                Text("Save changes this profile only. It never runs Planner Preview or Apply.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { ActionButton({ scope.launch { c.saveProfileFromUser() } }, enabled = !busy,
            modifier = Modifier.testTag("profile-edit-save")) { Text("Save profile") } },
        dismissButton = { ActionButton(c::requestCloseProfileEditor, enabled = !busy, role = ActionRole.TERTIARY) { Text("Cancel editing") } })
    if (c.discardEditRequested) DraftDiscardPrompt(c::discardProfileDraft, c::keepProfileDraft)
    if (c.reloadEditRequested) AlertDialog(onDismissRequest = c::keepProfileDraft,
        title = { Text("Discard draft and reload?") },
        text = { Text("This replaces your draft with the current authoritative profile and starts a new edit session. Nothing is saved.") },
        confirmButton = { ActionButton({ scope.launch { c.confirmReloadProfileFromUser() } }, enabled = !busy) { Text("Discard draft and reload") } },
        dismissButton = { ActionButton(c::keepProfileDraft, role = ActionRole.TERTIARY) { Text("Keep editing") } })
}

fun allDayPolicyLabel(policy: AllDayEventPolicy): String = when (policy) {
    AllDayEventPolicy.NON_BLOCKING -> "Visible only - does not block planning"
    AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY -> "Block the whole local day"
}
