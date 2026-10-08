package dev.agenticscheduler.desktop

import androidx.room3.withWriteTransaction
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.fixtures.D10ProductFixtureGraph
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.presentation.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DayOfWeek
import org.junit.Test
import java.io.File

/** Canonical coordinator + real Application/MutationCoordinator/Room, never a fake Save layer. */
class PlanningProfileWorkspaceTest {
    @Test fun unconfiguredOpeningCapturesExactSnapshot() = fixture { f ->
        val c = f.workspace(); c.refresh(); c.openProfileEditor(f.unconfigured)
        check(c.profileEditor!!.expectedBefore === f.unconfigured)
        check(c.profileEditor!!.draft == PlanningProfileDraft.from(f.unconfigured))
        check(!c.profileEditor!!.dirty)
    }
    @Test fun explicitlyConfigureUnconfiguredProfile() = fixture { f ->
        val c = f.workspace(); c.refresh(); c.openProfileEditor(f.unconfigured)
        c.updateProfileDraft(PlanningProfileDraft.from(f.configured).copy(name = f.unconfigured.name))
        c.saveProfileFromUser()
        check(c.profileResult is PlanningProfileSettingsResult.Success)
        check(f.base.profiles.get(f.unconfigured.id)!!.configuration == f.configured.configuration)
    }
    @Test fun configuredProfileEditCommitsReturnedValue() = fixture { f ->
        val c = opened(f); c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "Explicit revised hours"))
        c.saveProfileFromUser(); check(c.profileEditor == null)
        check(c.lastCommittedProfile == f.base.profiles.get(f.configured.id))
        check(c.lastCommittedProfile!!.name == "Explicit revised hours")
    }
    @Test fun successIsOrdinaryUserMutationWithExactBeforeImage() = fixture { f ->
        val c = opened(f); val count = f.base.journal.timeline().size
        c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "User settings")); c.saveProfileFromUser()
        val operation = f.base.journal.timeline().drop(count).single().operation
        check(operation.origin == MutationOrigin.User)
        val put = operation.orderedMutations.single() as dev.agenticscheduler.sync.PlanningProfilePut
        check(put.before!!.name == f.configured.name); check(put.after.name == "User settings")
    }
    @Test fun successRefreshesAuthoritativeSelection() = fixture { f ->
        val c = opened(f); c.updateProfileDraft(c.profileEditor!!.draft.copy(preferred = "45m")); c.saveProfileFromUser()
        check(c.draft.profileId == f.configured.id)
        check(c.profiles.single { it.id == f.configured.id } == c.lastCommittedProfile)
    }
    @Test fun saveNeverPreviewsAppliesOrChangesFocusBlocks() = fixture { f ->
        val c = opened(f); c.draft = f.request(); c.requestPreview()
        val branch = c.preview; val blocks = f.base.tasks.observeFocusBlocks().first(); val count = f.base.journal.timeline().size
        c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "Profile only")); c.saveProfileFromUser()
        check(c.preview === branch); check(f.base.tasks.observeFocusBlocks().first() == blocks)
        check(f.base.journal.timeline().size == count + 1); check(f.base.providerRequests == 0)
    }
    @Test fun concurrentSourceRefreshDoesNotReplaceOpeningSnapshot() = fixture { f ->
        val c = opened(f); changeSource(f); c.refresh()
        check(c.profiles.single { it.id == f.configured.id }.name == "Concurrent B")
        check(c.profileEditor!!.expectedBefore === f.configured)
        c.saveProfileFromUser(); check(c.profileResult == PlanningProfileSettingsResult.Stale)
    }
    @Test fun staleRetainsExactDraftAndSnapshot() = fixture { f ->
        val c = opened(f); c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "Unsaved C")); val session = c.profileEditor
        changeSource(f); c.saveProfileFromUser(); check(c.profileEditor == session)
        check(c.profileFeedback!!.contains("changed since editing began"))
    }
    @Test fun staleDoesNotWriteOrAdvanceCausality() = fixture { f ->
        val c = opened(f); changeSource(f)
        val journal = f.base.journal.timeline(); val causal = f.base.journal.localReplicaState()
        c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "C")); c.saveProfileFromUser()
        check(f.base.profiles.get(f.configured.id)!!.name == "Concurrent B")
        check(f.base.journal.timeline() == journal); check(f.base.journal.localReplicaState() == causal)
    }
    @Test fun reloadRequiresExplicitConfirmedUserDiscard() = fixture { f ->
        val c = opened(f); c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "C")); val draft = c.profileEditor
        changeSource(f); c.saveProfileFromUser(); c.confirmReloadProfileFromUser(); check(c.profileEditor == draft)
        c.requestReloadProfileFromUser(); check(c.profileEditor == draft); c.keepProfileDraft(); check(c.profileEditor == draft)
        c.requestReloadProfileFromUser(); c.confirmReloadProfileFromUser()
        check(c.profileEditor!!.expectedBefore == f.base.profiles.get(f.configured.id))
        check(c.profileEditor!!.draft.name == "Concurrent B"); check(!c.profileEditor!!.dirty)
    }
    @Test fun notFoundRetainsDraftWithoutRecreatingIdentity() = fixture { f ->
        val c = f.workspace(); c.refresh(); c.openProfileEditor(f.unconfigured)
        c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "Missing draft")); val session = c.profileEditor
        // Test-only adversarial source disappearance; product has no profile-deletion capability.
        f.db.withWriteTransaction { usePrepared("DELETE FROM planning_profiles WHERE id = ?") { it.bindText(1, f.unconfigured.id.value); it.step() } }
        val count = f.base.journal.timeline().size; c.saveProfileFromUser()
        check(c.profileResult == PlanningProfileSettingsResult.NotFound); check(c.profileEditor == session)
        check(f.base.profiles.get(f.unconfigured.id) == null); check(f.base.journal.timeline().size == count)
    }
    @Test fun businessConflictRetainsDraftAndWritesNothing() = fixture { f ->
        val c = opened(f); c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "Blocked draft")); val session = c.profileEditor
        f.profileConflict(); val journal = f.base.journal.timeline(); val causal = f.base.journal.localReplicaState()
        c.saveProfileFromUser(); check(c.profileResult is PlanningProfileSettingsResult.BlockedBySyncConflict)
        check(c.profileEditor == session); check(f.base.journal.timeline() == journal); check(f.base.journal.localReplicaState() == causal)
        check(f.conflicts.listOpen(f.space).size == 1)
    }
    @Test fun invalidZoneNeverCallsSave() = fixture { f -> invalid(f) { it.copy(timeZone = "Not/AZone") } }
    @Test fun durationOrderIsRejectedWithoutClamping() = fixture { f -> invalid(f) { it.copy(minimum = "2h", preferred = "50m", maximum = "90m") } }
    @Test fun overlappingAndInvalidAvailabilityUseDomainValidation() = fixture { f ->
        invalid(f) { it.copy(availability = listOf(AvailabilityRowDraft(DayOfWeek.TUESDAY,"09:00","12:00"), AvailabilityRowDraft(DayOfWeek.TUESDAY,"11:00","13:00")).toImmutableList()) }
        invalid(f) { it.copy(availability = listOf(AvailabilityRowDraft(DayOfWeek.TUESDAY,"12:00","09:00")).toImmutableList()) }
    }
    @Test fun dirtyCloseKeepsDraftUntilExplicitDiscard() = fixture { f ->
        val c = opened(f); c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "Unsaved")); val journal = f.base.journal.timeline()
        c.requestCloseProfileEditor(); check(c.discardEditRequested); check(c.profileEditor != null)
        c.keepProfileDraft(); check(c.profileEditor!!.draft.name == "Unsaved")
        c.requestCloseProfileEditor(); c.discardProfileDraft(); check(c.profileEditor == null)
        check(f.base.journal.timeline() == journal)
    }
    @Test fun configuredCannotBecomeUnconfiguredAndDurationRoundTripIsExact() = fixture { f ->
        val c = opened(f); val opening = c.profileEditor
        c.updateProfileDraft(opening!!.draft.copy(configured = false)); check(c.profileEditor == opening)
        check(opening.draft.toProfile(f.configured.id) == f.configured)
    }
    @Test fun plannerAndSettingsShareSessionAcrossNavigationAndRefreshWithoutSaving() = fixture { f ->
        val c = opened(f); c.updateProfileDraft(c.profileEditor!!.draft.copy(name = "Retained")); val opening = c.profileEditor
        val nav = DesktopNavigation(); nav.open(DesktopDestination.PLANNER); nav.open(DesktopDestination.SETTINGS)
        c.refresh(); check(c.profileEditor == opening)
        val count = f.base.journal.timeline().size; c.requestCloseProfileEditor(); c.keepProfileDraft()
        check(f.base.journal.timeline().size == count); check(f.base.tasks.observeFocusBlocks().first().isEmpty())
    }
    private suspend fun opened(f: D10ProductFixtureGraph) = f.workspace().also { it.refresh(); it.openProfileEditor(f.configured) }
    private suspend fun changeSource(f: D10ProductFixtureGraph) { check(f.profileSettings.save(f.configured.copy(name = "Concurrent B"), f.configured) is PlanningProfileSettingsResult.Success) }
    private suspend fun invalid(f: D10ProductFixtureGraph, change: (PlanningProfileDraft) -> PlanningProfileDraft) {
        val c = opened(f); c.updateProfileDraft(change(c.profileEditor!!.draft)); val session = c.profileEditor
        val journal = f.base.journal.timeline(); val causal = f.base.journal.localReplicaState(); c.saveProfileFromUser()
        check(c.profileEditor == session); check(c.profileFeedback != null); check(c.profileResult == null)
        check(f.base.journal.timeline() == journal); check(f.base.journal.localReplicaState() == causal)
    }
    private fun fixture(test: suspend (D10ProductFixtureGraph) -> Unit) {
        val file = File.createTempFile("profile-workspace-", ".db"); val db = openDesktopDatabase(file.absolutePath); val f = D10ProductFixtureGraph(db)
        try { runBlocking { f.seed(); test(f) } } finally { f.base.client.close(); db.close(); file.delete() }
    }
}
