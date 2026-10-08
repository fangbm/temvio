package dev.agenticscheduler.presentation

import androidx.compose.runtime.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.planner.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

enum class PlannerMode { FULL_REPLAN, LOCAL_REFLOW }

/** Explicit request draft. No clock, horizon, profile or timezone is inferred. */
data class PlannerRequestDraft(
    val mode: PlannerMode = PlannerMode.FULL_REPLAN,
    val profileId: PlanningProfileId? = null,
    val referenceNow: String = "",
    val horizonStart: String = "",
    val horizonEnd: String = "",
    val affectedIds: Set<FocusBlockId> = emptySet(),
    val searchStart: String = "",
    val searchEnd: String = "",
    val disruptedRanges: String = "",
)

data class PlannerPlacementRow(val kind: String, val task: String, val before: String?, val after: String?)

/** Session-owned UI orchestration. Application revalidation is the sole Apply authority. */
class PlannerWorkspaceCoordinator(
    private val reads: ConflictAwareSourceFactReadService,
    private val planner: DogfoodPlannerService,
    private val settings: PlanningProfileSettingsService,
) {
    var draft by mutableStateOf(PlannerRequestDraft())
    var profiles by mutableStateOf<List<PlanningProfile>>(emptyList()); private set
    var tasks by mutableStateOf<List<Task>>(emptyList()); private set
    var focusBlocks by mutableStateOf<List<FocusBlock>>(emptyList()); private set
    var sourceStatus by mutableStateOf<String?>(null); private set
    var preview by mutableStateOf<PlannerPreview?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var createName by mutableStateOf<String?>(null)
    var discardProfileRequested by mutableStateOf(false)

    var profileEditor by mutableStateOf<PlanningProfileEditSession?>(null); private set
    var profileResult by mutableStateOf<PlanningProfileSettingsResult?>(null); private set
    var profileFeedback by mutableStateOf<String?>(null); private set
    var discardEditRequested by mutableStateOf(false); private set
    var reloadEditRequested by mutableStateOf(false); private set
    var lastCommittedProfile by mutableStateOf<PlanningProfile?>(null); private set

    fun openProfileEditor(profile: PlanningProfile) {
        if (busy || profileEditor != null) return
        profileEditor = PlanningProfileEditSession(profile)
        profileResult = null
        profileFeedback = null
    }

    fun updateProfileDraft(next: PlanningProfileDraft) {
        if (busy) return
        val session = profileEditor ?: return
        // Configured -> Unconfigured is deliberately not an editor capability.
        if (session.expectedBefore.configuration is dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Configured && !next.configured) return
        profileEditor = session.copy(draft = next)
    }

    fun requestCloseProfileEditor() {
        if (busy) return
        if (profileEditor?.dirty == true) discardEditRequested = true else closeProfileEditor()
    }
    fun keepProfileDraft() { discardEditRequested = false; reloadEditRequested = false }
    fun discardProfileDraft() { if (!busy) closeProfileEditor() }
    private fun closeProfileEditor() {
        profileEditor = null; discardEditRequested = false; reloadEditRequested = false
        profileFeedback = null; profileResult = null
    }

    fun requestReloadProfileFromUser() { if (!busy && profileEditor != null) reloadEditRequested = true }
    suspend fun confirmReloadProfileFromUser() = command {
        if (!reloadEditRequested) return@command
        reloadEditRequested = false
        val id = profileEditor?.expectedBefore?.id ?: return@command
        loadSources()
        val fresh = profiles.firstOrNull { it.id == id }
        if (fresh == null) {
            profileFeedback = "Current profile is missing or unavailable. Draft retained; no identity was recreated."
        } else {
            profileEditor = PlanningProfileEditSession(fresh)
            profileResult = null
            profileFeedback = "Current profile reloaded. Previous draft discarded by your explicit choice."
        }
    }

    suspend fun saveProfileFromUser() = command {
        val session = profileEditor ?: return@command
        val proposed = try { session.draft.toProfile(session.expectedBefore.id) }
        catch (invalid: IllegalArgumentException) { profileFeedback = invalid.message; return@command }
        val result = settings.save(proposed, session.expectedBefore)
        profileResult = result
        when (result) {
            is PlanningProfileSettingsResult.Success -> {
                lastCommittedProfile = result.profile
                profileEditor = null
                discardEditRequested = false; reloadEditRequested = false
                profiles = profiles.map { if (it.id == result.profile.id) result.profile else it }
                draft = draft.copy(profileId = result.profile.id)
                message = "Planning profile saved. Schedule unchanged; no Planner Preview or Apply was run."
                try { loadSources() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Planning profile committed. Source refresh failed; refresh before planning." }
            }
            PlanningProfileSettingsResult.Stale -> profileFeedback = "Profile changed since editing began. Draft retained; reload current profile explicitly before retrying."
            PlanningProfileSettingsResult.NotFound -> profileFeedback = "Profile no longer exists. Draft retained; it cannot recreate this identity."
            is PlanningProfileSettingsResult.BlockedBySyncConflict -> profileFeedback = "Save blocked by an open business sync conflict. Draft retained; no changes committed."
        }
    }

    suspend fun refresh() = command { loadSources() }

    private suspend fun loadSources() {
        val p = reads.observePlanningProfiles().first()
        val t = reads.observeTasks().first()
        val f = reads.observeFocusBlocks().first()
        profiles = (p as? ConflictAwareRead.Projected)?.value.orEmpty()
        tasks = (t as? ConflictAwareRead.Projected)?.value.orEmpty()
        focusBlocks = (f as? ConflictAwareRead.Projected)?.value.orEmpty()
        sourceStatus = when {
            listOf(p,t,f).any { it is ConflictAwareRead.Unprojectable } -> "Source facts unavailable: resolve the business sync conflict before planning."
            listOf(p,t,f).filterIsInstance<ConflictAwareRead.Projected<*>>().any { it.syncConflictRefs.isNotEmpty() } -> "Provisional source facts: open business sync conflicts remain."
            else -> null
        }
        loaded = true
    }

    suspend fun requestPreview() = command {
        val input = draft
        val profile = profiles.firstOrNull { it.id == input.profileId }
            ?: throw IllegalArgumentException("Select an existing PlanningProfile.")
        val reference = Instant.parse(input.referenceNow.trim())
        val horizon = PlanningHorizon(Instant.parse(input.horizonStart.trim()), Instant.parse(input.horizonEnd.trim()))
        val result = when (input.mode) {
            PlannerMode.FULL_REPLAN -> planner.fullReplan(profile.id, reference, horizon)
            PlannerMode.LOCAL_REFLOW -> {
                val zone = (profile.configuration as? dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Configured)?.timeZone
                    ?: throw IllegalArgumentException("Local Reflow requires a configured profile.")
                val search = ZonedTimeRange(Instant.parse(input.searchStart.trim()), Instant.parse(input.searchEnd.trim()), zone)
                val disruptions = input.disruptedRanges.lines().filter { it.isNotBlank() }.map { line ->
                    val parts = line.trim().split(" / ")
                    require(parts.size == 2) { "Each disrupted range needs start / end Instants." }
                    ZonedTimeRange(Instant.parse(parts[0]), Instant.parse(parts[1]), zone)
                }.toImmutableList()
                require(input.affectedIds.isNotEmpty() || disruptions.isNotEmpty()) { "Select affected FocusBlocks or enter disrupted ranges." }
                planner.localReflow(profile.id, reference, horizon, LocalReflowRequest(input.affectedIds.sortedBy { it.value }.toImmutableList(), disruptions, search))
            }
        }
        preview = result
        message = "Preview is session-local. No business change was committed."
    }

    suspend fun apply(applyNow: Instant) = command {
        val branch = (preview as? PlannerPreview.Applicable)?.branch ?: return@command
        if (branch.status != PlanBranchStatus.DRAFT) { message = "A fresh preview is required before Apply."; return@command }
        when (val result = planner.apply(branch, applyNow)) {
            is PlanBranchApplyResult.Applied -> {
                preview = null // Consume before refreshing: failed read must never re-enable Apply.
                message = "Applied as one committed Planner mutation. Preview consumed."
                try { loadSources() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Planner mutation committed and preview consumed. Source refresh failed; refresh before further planning." }
            }
            is PlanBranchApplyResult.Stale -> { preview = PlannerPreview.Applicable(result.branch); message = "Stale preview. No changes applied; request a fresh preview." }
            is PlanBranchApplyResult.BlockedBySyncConflict -> { preview = PlannerPreview.Applicable(result.branch); message = "Apply blocked by an open business sync conflict. No changes applied." }
        }
    }

    fun cancelPreview() { if (!busy) { preview = null; message = "Preview discarded. Active schedule and History unchanged." } }

    suspend fun createUnconfigured() = command {
        val name = requireNotNull(createName).trim()
        require(name.isNotBlank()) { "Enter a profile name." }
        when (settings.createUnconfigured(name)) {
            is PlanningProfileSettingsResult.Success -> { createName = null; message = "Unconfigured profile created. No scheduling defaults were added."; loadSources() }
            PlanningProfileSettingsResult.Stale -> message = "Profile creation was stale; refresh before retry."
            PlanningProfileSettingsResult.NotFound -> message = "Profile no longer exists."
            is PlanningProfileSettingsResult.BlockedBySyncConflict -> message = "Profile creation blocked by business sync conflict."
        }
    }

    fun placements(branch: PlanBranch): List<PlannerPlacementRow> = branch.mutations.map { mutation ->
        val task = branch.baseFacts.tasks.firstOrNull { it.id == mutation.taskId }?.title ?: "Task ${mutation.taskId.value}"
        fun before(id: FocusBlockId) = branch.baseFacts.focusBlocks.firstOrNull { it.id == id }?.time?.displayRange()
        when (mutation) {
            is FocusBlockMutation.Create -> PlannerPlacementRow("Create FocusBlock",task,null,mutation.draft.time.displayRange())
            is FocusBlockMutation.Move -> PlannerPlacementRow("Move FocusBlock",task,before(mutation.id),mutation.time.displayRange())
            is FocusBlockMutation.Resize -> PlannerPlacementRow("Resize FocusBlock",task,before(mutation.id),mutation.time.displayRange())
            is FocusBlockMutation.Delete -> PlannerPlacementRow("Delete FocusBlock",task,before(mutation.id),null)
        }
    }

    private suspend fun command(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { message = "Check explicit profile, Instant ranges and request inputs. Nothing was silently saved or forced." }
        catch (_: Exception) { message = "Unable to finish the operation. Refresh authoritative facts before retrying." }
        finally { busy = false }
    }
}

fun ZonedTimeRange.displayRange(): String = "$start → $endExclusive · ${timeZone.id}"

fun plannerIssueLabel(issue: PlannerIssue): String = when (issue) {
    PlannerIssue.ProfileUnconfigured -> "Profile is Unconfigured: explicit availability and policies are required."
    is PlannerIssue.InvalidSnapshot -> "Invalid source facts: ${issue.reason}"
    is PlannerIssue.TimeResolutionFailure -> "Time cannot be resolved without an explicit choice: ${issue.localValue}"
    is PlannerIssue.UnknownRemainingEffort -> "Unknown remaining effort · Task ${issue.taskId.value}"
    is PlannerIssue.NoLegalAvailability -> "No legal availability · Task ${issue.taskId.value}"
    is PlannerIssue.DependencyBlocked -> "Task ${issue.taskId.value} blocked by prerequisite ${issue.prerequisiteTaskId.value}"
    is PlannerIssue.OverflowApprovalRequired -> "Overflow approval required · Task ${issue.taskId.value}"
    is PlannerIssue.HardDeadlineShortfall -> "Hard deadline shortfall: ${issue.remaining} · Task ${issue.taskId.value}"
    is PlannerIssue.UnscheduledEffort -> "Unscheduled effort: ${issue.remaining} · Task ${issue.taskId.value}"
    is PlannerIssue.OverallocatedPlannedEffort -> "Overallocated planned effort · Task ${issue.taskId.value}"
    is PlannerIssue.ImmovableConflict -> "Immovable FocusBlock conflict · ${issue.focusBlockId.value}"
}
