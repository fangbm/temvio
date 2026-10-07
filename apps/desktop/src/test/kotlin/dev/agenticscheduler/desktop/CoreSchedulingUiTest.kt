package dev.agenticscheduler.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asSkiaBitmap
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.fixtures.D10CoreFixtureGraph
import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.sync.TaskPut
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

@OptIn(ExperimentalTestApi::class)
class CoreSchedulingUiTest {
    @Test fun calendarModesOverflowAndListAreReadOnly() = fixture { f ->
        val b = f.base; val count = runBlocking { b.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1000) {
            val nav = DesktopNavigation().also { it.open(DesktopDestination.CALENDAR) }; val core = CoreScreenCoordinator()
            setContent { app(f,nav,core) }
            waitUntil(timeoutMillis=10000) { onAllNodesWithTag("schedule-ready").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("calendar-mode-WEEK").performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText(" more",substring=true).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText(" more",substring=true).onFirst().performClick()
            onNodeWithText("2026-10-06 · all items").assertExists()
            onNodeWithText("Close date list").performClick()
            onNodeWithText("Accessible list").performClick()
            onNodeWithText("Grid view").assertExists()
            onNodeWithTag("calendar-mode-MONTH").performScrollTo().performClick()
            runOnIdle { check(core.mode == CalendarMode.MONTH) }
            onNodeWithTag("desktop-nav-TASKS").performClick(); onNodeWithText("Completed",substring=false).performClick()
            onNodeWithText("No matching tasks").assertExists(); onNodeWithText("All",substring=false).performClick()
            onNodeWithText("Prepare research notes").assertExists()
            check(b.providerRequests == 0); runBlocking { check(b.journal.timeline().size == count) }
        }
    }

    @Test fun taskDetailEditCommitsActualD7AndInvalidCancelDoesNotWrite() = fixture { f ->
        val b = f.base; val count = runBlocking { b.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1000) {
            val nav = DesktopNavigation().also { it.open(DesktopDestination.TASKS) }
            setContent { app(f,nav) }
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("View Task").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("View Task").performClick(); onNodeWithText("Linked FocusBlocks").assertExists()
            onNodeWithText("Edit Task").performClick(); onNodeWithText("Title",substring=false).performTextReplacement("Edited task")
            onNodeWithText("Save",substring=false).performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("Title",substring=false).fetchSemanticsNodes().isEmpty() }
            runBlocking {
                val added = b.journal.timeline().drop(count); check(added.size == 1)
                val put = added.single().operation.orderedMutations.single() as TaskPut
                check(put.before!!.title == "Prepare research notes" && put.after.title == "Edited task")
            }
            onNodeWithText("New Task").performClick(); onNodeWithText("Title",substring=false).performTextInput("Discard me")
            onNodeWithText("Remaining effort (optional)").performTextInput("invalid")
            onNodeWithText("Save",substring=false).performClick(); onNodeWithText("Enter valid effort and deadline values.").assertExists()
            onNodeWithText("Cancel",substring=false).performClick(); onNodeWithText("Discard draft").performClick()
            runBlocking { check(b.journal.timeline().size == count+1) }
        }
    }

    @Test fun courseSaveThenRuleSaveAreIndependentRealUiCommands() = fixture { f ->
        val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
        val count = runBlocking { f.base.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1100) {
            val nav = DesktopNavigation().also { it.open(DesktopDestination.COURSES) }
            setContent { app(f,nav,academic=c) }
            waitUntil(timeoutMillis=10000) { onAllNodesWithTag("academic-ready").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("New Course",substring=false).performClick()
            onNodeWithText("Name",substring=false).performTextInput("Explicit UI Course")
            onNodeWithText("Semester: Choose").performClick(); onNodeWithText("Autumn semester",substring=false).performClick()
            onNodeWithText("Save",substring=false).performClick(); waitUntil(timeoutMillis=10000) { c.draft == null && !c.saving && !c.loading }
            runBlocking { check(f.base.journal.timeline().size == count+1); check(f.base.academics.observeCourseScheduleRules().first().size == 2) }
            println("After save selected=${c.selected}; courses=${c.facts?.courses}; saving=${c.saving}; loading=${c.loading}; error=${c.technicalError}")
            try { waitUntil(timeoutMillis=10000) { onAllNodesWithText("New CourseScheduleRule",substring=false).fetchSemanticsNodes().isNotEmpty() } }
            catch (failure: Throwable) {
                println("Post-save selected=${c.selected}; read=${c.read}; saving=${c.saving}; loading=${c.loading}; technical=${c.technicalError}")
                println(onRoot().printToString())
                throw failure
            }
            onNodeWithText("New CourseScheduleRule",substring=false).performClick()
            onNodeWithText("Weekday: Choose").performScrollTo().performClick(); onNodeWithText("WEDNESDAY",substring=false).performClick()
            onNodeWithText("Teaching weeks (explicit comma-separated numbers)").performScrollTo().performTextInput("2")
            onNodeWithText("Rule time: Choose").performScrollTo().performClick(); onNodeWithText("CLOCK_TIME",substring=false).performClick()
            onNodeWithText("Local start (HH:MM)").performScrollTo().performTextInput("14:00")
            onNodeWithText("Local end exclusive (HH:MM)").performScrollTo().performTextInput("15:00")
            onNodeWithText("Save",substring=false).performClick(); waitUntil(timeoutMillis=10000) { c.draft == null && !c.saving && !c.loading }
            runBlocking { check(f.base.journal.timeline().size == count+2) }; check(f.base.providerRequests == 0)
        }
    }

    @Test fun explicitSetupYearSemesterAndTemplateAreUsableFromForms() = fixture { f ->
        val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
        runDesktopComposeUiTest(width=1280,height=1100) {
            val nav = DesktopNavigation().also { it.open(DesktopDestination.COURSES) }; setContent { app(f,nav,academic=c) }
            waitUntil(timeoutMillis=10000) { c.facts != null }
            onNodeWithText("AcademicYear",substring=false).performClick(); onNodeWithText("New AcademicYear").performClick()
            onNodeWithText("Name",substring=false).performTextInput("UI Year")
            onNodeWithText("Start date (YYYY-MM-DD)").performTextInput("2027-09-01"); onNodeWithText("End exclusive date").performTextInput("2028-09-01")
            onNodeWithText("Save",substring=false).performClick(); waitUntil(timeoutMillis=10000) { c.draft == null && !c.saving && !c.loading }
            waitUntil(timeoutMillis=10000) { c.facts?.academicYears?.any { it.name == "UI Year" } == true }
            check(c.facts!!.academicYears.any { it.name == "UI Year" })
            onNodeWithText("Semester",substring=false).performClick(); onNodeWithText("New Semester").performClick()
            onNodeWithText("Name",substring=false).performTextInput("UI Semester")
            onNodeWithText("AcademicYear: Choose").performClick()
            waitUntil(timeoutMillis=10000) { onAllNodesWithText("UI Year",substring=false).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("UI Year",substring=false).performClick()
            onNodeWithText("Start date (YYYY-MM-DD)").performScrollTo().performTextInput("2027-09-01")
            onNodeWithText("End exclusive date").performScrollTo().performTextInput("2027-12-01")
            onNodeWithText("Time zone",substring=false).performScrollTo().performTextInput("UTC")
            onNodeWithText("Add explicit week").performScrollTo().performClick()
            onNodeWithText("Week 1 number").performScrollTo().performTextInput("1")
            onNodeWithText("Week 1 start").performScrollTo().performTextInput("2027-09-01")
            onNodeWithText("Week 1 end exclusive").performScrollTo().performTextInput("2027-09-08")
            onNodeWithText("Save",substring=false).performClick(); waitUntil(timeoutMillis=10000) { c.draft == null && !c.saving && !c.loading }
            onNodeWithText("PeriodTemplate",substring=false).performClick(); onNodeWithText("New PeriodTemplate").performClick()
            onNodeWithText("Name",substring=false).performTextInput("UI Periods"); onNodeWithText("Add explicit period").performScrollTo().performClick()
            onNodeWithText("Period 1 number").performScrollTo().performTextInput("1")
            onNodeWithText("Period 1 start (HH:MM)").performScrollTo().performTextInput("08:00")
            onNodeWithText("Period 1 end exclusive").performScrollTo().performTextInput("08:45")
            onNodeWithText("Save",substring=false).performClick(); waitUntil(timeoutMillis=10000) { c.draft == null && !c.saving && !c.loading }
            check((c.selected as AcademicRecord.Template).value.periods.size == 1)
        }
    }

    @Test fun examFormSupportsAllThreeScheduleChoices() = fixture { f ->
        val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
        runDesktopComposeUiTest(width=1280,height=1100) {
            val nav = DesktopNavigation().also { it.open(DesktopDestination.EXAMS) }; setContent { app(f,nav,academic=c) }
            waitUntil(timeoutMillis=10000) { c.facts != null }
            for (choice in ExamScheduleChoice.entries) {
                onNodeWithText("New Exam",substring=false).performClick(); onNodeWithText("Title",substring=false).performTextInput("UI $choice")
                onNodeWithText("Semester: Choose").performClick(); onNodeWithText("Autumn semester",substring=false).performClick()
                onNodeWithText("Exam schedule: Choose").performScrollTo().performClick(); onNodeWithText(choice.name,substring=false).performClick()
                if (choice == ExamScheduleChoice.DATE_ONLY) onNodeWithText("Exam date (YYYY-MM-DD)").performScrollTo().performTextInput("2026-10-06")
                if (choice == ExamScheduleChoice.EXACT) { onNodeWithText("Exact start (YYYY-MM-DDTHH:MM)").performScrollTo().performTextInput("2026-10-06T14:00"); onNodeWithText("Exact end exclusive").performScrollTo().performTextInput("2026-10-06T15:00") }
                onNodeWithText("Save",substring=false).performClick(); waitUntil(timeoutMillis=10000) { c.draft == null && !c.saving && !c.loading }
                check(c.selected is AcademicRecord.Assessment)
            }
        }
    }

    @Test fun dirtyAcademicDraftSurvivesResizeAndThemeAndExplicitDiscard() = fixture { f ->
        val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor); val count = runBlocking { f.base.journal.timeline().size }
        runDesktopComposeUiTest(width=1280,height=1000) {
            var width by mutableStateOf(1280f); val nav = DesktopNavigation().also { it.open(DesktopDestination.COURSES) }
            setContent { Box(Modifier.requiredWidth(width.dp)) { app(f,nav,academic=c) } }
            waitUntil(timeoutMillis=10000) { c.facts != null }; onNodeWithText("New Course",substring=false).performClick()
            onNodeWithText("Name",substring=false).performTextInput("Unsent form draft")
            runOnIdle { width = 640f }; onNodeWithText("Unsent form draft").assertExists()
            onNodeWithText("Cancel",substring=false).performClick(); onNodeWithText("Discard unsaved changes?").assertExists()
            onNodeWithText("Keep editing").performClick(); runOnIdle { width = 1280f }
            onNodeWithText("Unsent form draft").assertExists(); onNodeWithText("Cancel",substring=false).performClick(); onNodeWithText("Discard draft").performClick()
            onNodeWithTag("theme-toggle").performClick(); runBlocking { check(f.base.journal.timeline().size == count) }; check(f.base.providerRequests == 0)
        }
    }

    @Test fun actualD10CoreComposeScreenshotCandidates() = fixture { f ->
        val out = File("build/d10-02/screenshots").also { it.mkdirs() }
        val variants = listOf(Triple(640,720,1f),Triple(1024,900,1f),Triple(1280,900,1f),Triple(1440,960,1f),Triple(1920,1080,1f),Triple(640,900,2f))
        val coreCases = listOf("today","calendar-day","calendar-week","calendar-month","tasks","courses","exams","course-detail","exam-detail","course-form","rule-form","semester-form","exam-form")
        for ((width,height,scale) in variants) for (dark in listOf(false,true)) {
            for (case in if (width == 1024) coreCases else if (width == 1280) listOf("calendar-month","semester-form","course-detail","exam-detail") else listOf("calendar-month","semester-form")) {
                runDesktopComposeUiTest(width=width,height=height) {
                    val nav = DesktopNavigation().also { it.open(when { case == "today" -> DesktopDestination.TODAY; case.startsWith("calendar") -> DesktopDestination.CALENDAR; case == "tasks" -> DesktopDestination.TASKS; case == "exams" || case.startsWith("exam-") -> DesktopDestination.EXAMS; else -> DesktopDestination.COURSES }) }
                    val core = CoreScreenCoordinator().also { it.mode = when (case) { "calendar-week" -> CalendarMode.WEEK; "calendar-month" -> CalendarMode.MONTH; else -> CalendarMode.DAY } }
                    val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
                    setContent { CompositionLocalProvider(LocalDensity provides Density(1f,scale)) { app(f,nav,core,c,dark) } }
                    val academic = nav.current in listOf(DesktopDestination.COURSES,DesktopDestination.EXAMS)
                    waitUntil(timeoutMillis=10000) { onAllNodesWithTag(if (academic) "academic-ready" else "schedule-ready").fetchSemanticsNodes().isNotEmpty() && (!academic || c.facts != null) }
                    if (case == "course-detail") runOnIdle { c.selected = AcademicRecord.Subject(c.facts!!.courses.first()) }
                    if (case == "exam-detail") runOnIdle { c.selected = AcademicRecord.Assessment(c.facts!!.exams.first()) }
                    if (case == "course-form") runOnIdle { c.edit(AcademicRecord.Subject(c.facts!!.courses.first())) }
                    if (case == "rule-form") runOnIdle { c.edit(AcademicRecord.Rule(c.facts!!.courseScheduleRules.first())) }
                    if (case == "semester-form") runOnIdle { c.edit(AcademicRecord.Term(f.semester)) }
                    if (case == "exam-form") runOnIdle { c.edit(AcademicRecord.Assessment(c.facts!!.exams.first { it.schedule is dev.agenticscheduler.domain.academic.ExamSchedule.Exact })) }
                    val expectedFormTitle = when (case) { "semester-form" -> "Edit Semester"; "exam-form" -> "Edit Exam"; "course-form" -> "Edit Course"; "rule-form" -> "Edit CourseScheduleRule"; else -> null }
                    if (expectedFormTitle != null) waitUntil(timeoutMillis=10000) { onAllNodesWithText(expectedFormTitle,substring=false).fetchSemanticsNodes().isNotEmpty() }
                    if (case.endsWith("-detail")) waitUntil(timeoutMillis=10000) { onAllNodesWithText("Close detail",substring=false).fetchSemanticsNodes().isNotEmpty() }
                    waitForIdle()
                    val capture = if (expectedFormTitle != null) onNode(isRoot() and hasAnyDescendant(hasText(expectedFormTitle,substring=false)) and hasAnyDescendant(hasSetTextAction())) else if (case.endsWith("-detail")) onNode(isRoot() and hasAnyDescendant(hasText("Close detail",substring=false))) else onAllNodes(isRoot()).onFirst()
                    val file = File(out,"desktop-${width}x$height-font${(scale*100).toInt()}-${if(dark) "dark" else "light"}-$case.png")
                    Image.makeFromBitmap(capture.captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
                    check(f.base.providerRequests == 0)
                }
            }
        }
    }

    @Composable private fun app(f: D10CoreFixtureGraph, nav: DesktopNavigation, core: CoreScreenCoordinator = remember { CoreScreenCoordinator() },
        academic: AcademicScreenCoordinator? = null, dark: Boolean = false) {
        val b = f.base
        DesktopApp(b.reads,b.planner,b.profileSettings,b.eventEditor,b.taskEditor,null,{},b.run,b.agent,b.secrets,b.enrollments,b.ids,b.conversationSettings,
            remember { DesktopScheduleScreenCoordinator(b.reads,b.date,b.zone) },nav,dark,academicService=f.authoring,coreSession=core,academicSession=academic,presentationNow=f.now)
    }
    private fun fixture(test: (D10CoreFixtureGraph) -> Unit) {
        val file = java.io.File.createTempFile("d10-core-", ".db"); val db = openDesktopDatabase(file.absolutePath); val f = D10CoreFixtureGraph(db)
        try { runBlocking { f.seed() }; test(f) } finally { f.base.client.close(); db.close(); file.delete() }
    }
}
