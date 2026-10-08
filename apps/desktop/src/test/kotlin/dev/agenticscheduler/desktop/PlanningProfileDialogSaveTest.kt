package dev.agenticscheduler.desktop

import androidx.compose.ui.test.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.fixtures.D10FixtureGraph
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.ui.TemvioTheme
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class PlanningProfileDialogSaveTest {
    @Test fun staleSaveRetainsEditorDraftAndCommittedProfile() = fixture { f, a ->
        var closed = false
        runDesktopComposeUiTest(width = 1024, height = 1100) {
            setContent { TemvioTheme(dark = false) { PlanningProfileDialog(a, f.profileSettings, { closed = true }, {}) } }
            onNodeWithText("Profile name", substring = false).performTextReplacement("User C draft")
            val b = a.copy(name = "Concurrent B")
            runBlocking { check(f.profileSettings.save(b, a) is PlanningProfileSettingsResult.Success) }
            val before = runBlocking { f.journal.timeline() to f.journal.localReplicaState() }
            onNodeWithText("Save", substring = false).performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithText("Profile changed since", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("User C draft").assertExists()
            check(!closed)
            runBlocking { check(f.profiles.get(a.id) == b); check(before == (f.journal.timeline() to f.journal.localReplicaState())) }
        }
    }

    @Test fun blockedSaveRetainsDraftAndDoesNotCloseEditor() = fixture { f, a ->
        var closed = false
        val block = SyncConflictWriteBlock("conflict", EntityKind.PLANNING_PROFILE, a.id.value, listOf("name"))
        val settings = PlanningProfileSettingsService(f.profiles, f.ids, f.mutations, SyncConflictWritePolicy { listOf(block) })
        val before = runBlocking { f.journal.timeline() to f.journal.localReplicaState() }
        runDesktopComposeUiTest(width = 1024, height = 1100) {
            setContent { TemvioTheme(dark = false) { PlanningProfileDialog(a, settings, { closed = true }, {}) } }
            onNodeWithText("Profile name", substring = false).performTextReplacement("Blocked draft")
            onNodeWithText("Save", substring = false).performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithText("Profile intersects", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Blocked draft").assertExists()
            check(!closed)
            runBlocking { check(f.profiles.get(a.id) == a); check(before == (f.journal.timeline() to f.journal.localReplicaState())) }
        }
    }

    @Test fun successfulSaveInvokesCompletionAndPersistsUserDraft() = fixture { f, a ->
        var closed = false
        runDesktopComposeUiTest(width = 1024, height = 1100) {
            setContent { TemvioTheme(dark = false) { PlanningProfileDialog(a, f.profileSettings, { closed = true }, {}) } }
            onNodeWithText("Profile name", substring = false).performTextReplacement("User B")
            onNodeWithText("Save", substring = false).performClick()
            waitUntil(timeoutMillis = 10000) { closed }
            runBlocking { check(f.profiles.get(a.id) == a.copy(name = "User B")) }
        }
    }

    private fun fixture(test: (D10FixtureGraph, PlanningProfile) -> Unit) {
        val file = File.createTempFile("profile-dialog-", ".db")
        val db = openDesktopDatabase(file.absolutePath)
        val f = D10FixtureGraph(db)
        try {
            val profile = runBlocking {
                val created = (f.profileSettings.createUnconfigured("A") as PlanningProfileSettingsResult.Success).profile
                val configured = created.copy(configuration = PlanningProfileConfiguration.Configured(
                    TimeZone.UTC,
                    listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(12, 0))).toImmutableList(),
                    15.minutes, 30.minutes, 60.minutes, AllDayEventPolicy.NON_BLOCKING,
                ))
                check(f.profileSettings.save(configured, created) is PlanningProfileSettingsResult.Success)
                configured
            }
            test(f, profile)
        } finally {
            f.client.close()
            db.close()
            file.delete()
        }
    }
}
