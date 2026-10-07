package dev.agenticscheduler.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.fixtures.D10CoreFixtureGraph
import dev.agenticscheduler.presentation.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

class CoreSchedulingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun moreDetailAndDirtyFormBackRespectOwnedSurfaces() = fixture { f, _ ->
        val nav = AndroidNavigation(); val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
        compose.setContent { app(f,nav,academic=c) }
        primary("MORE"); compose.onNodeWithText("Courses",substring=false).performScrollTo().performClick()
        compose.waitUntil(10000) { c.facts != null }
        compose.onNodeWithText("View Course",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Selected detail").assertExists()
        back(); compose.onNodeWithText("Selected detail").assertDoesNotExist()
        compose.runOnIdle { check(nav.current == AndroidDestination.COURSES) }
        compose.onNodeWithText("New Course",substring=false).performClick()
        compose.onNodeWithText("Name",substring=false).performTextInput("Native unsaved draft")
        back() // Android may first dismiss the IME; the next Back owns the dirty dialog.
        if (compose.onAllNodesWithText("Discard unsaved changes?").fetchSemanticsNodes().isEmpty()) back()
        compose.onNodeWithText("Discard unsaved changes?").assertExists()
        compose.onNodeWithText("Keep editing").performClick(); compose.onNodeWithText("Native unsaved draft").assertExists()
        back()
        if (compose.onAllNodesWithText("Discard unsaved changes?").fetchSemanticsNodes().isEmpty()) back()
        compose.onNodeWithText("Discard draft").performClick(); back()
        if (nav.current == AndroidDestination.COURSES) back() // A remaining IME owns Back before the shell.
        compose.runOnIdle { check(nav.current == AndroidDestination.MORE) }; check(f.base.providerRequests == 0)
    }
    @Test fun explicitNativeCourseSaveUsesApplicationAndNoImplicitRule() = fixture { f, _ ->
        val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
        val nav = AndroidNavigation().also { it.open(AndroidDestination.COURSES) }
        val count = runBlocking { f.base.journal.timeline().size }
        compose.setContent { app(f,nav,academic=c) }; compose.waitUntil(10000) { c.facts != null }
        compose.onNodeWithText("New Course",substring=false).performClick()
        compose.onNodeWithText("Name",substring=false).performTextInput("Native authored course")
        compose.onNodeWithText("Semester: Choose").performClick(); compose.onNodeWithText("Autumn semester",substring=false).performClick()
        compose.onNodeWithText("Save",substring=false).performClick(); compose.waitUntil(10000) { c.draft == null && !c.saving && !c.loading }
        check((c.selected as AcademicRecord.Subject).value.name == "Native authored course")
        check(c.facts!!.courseScheduleRules.size == 2)
        runBlocking { check(f.base.journal.timeline().size == count+1) }; check(f.base.providerRequests == 0)
    }
    @Test fun nativeTaskDetailAndCalendarModesDoNotSubmitOrComplete() = fixture { f, _ ->
        val nav = AndroidNavigation().also { it.open(AndroidDestination.TASKS) }; val core = CoreScreenCoordinator()
        val count = runBlocking { f.base.journal.timeline().size }
        compose.setContent { app(f,nav,core) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("View Task",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View Task",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Linked FocusBlocks").assertExists(); back()
        primary("CALENDAR"); compose.onNodeWithTag("calendar-mode-WEEK").performScrollTo().performClick()
        compose.onNodeWithText("Accessible list").performScrollTo().performClick(); compose.onNodeWithText("Grid view").assertExists()
        compose.onNodeWithTag("calendar-mode-MONTH").performScrollTo().performClick()
        compose.onNodeWithTag("theme-toggle").performClick(); primary("TASKS")
        compose.runOnIdle { check(core.mode == CalendarMode.MONTH) }
        runBlocking { check(f.base.journal.timeline().size == count) }; check(f.base.providerRequests == 0)
    }
    @Test fun invalidNativeExamRetainsDraftAndNeverWrites() = fixture { f, _ ->
        val nav = AndroidNavigation().also { it.open(AndroidDestination.EXAMS) }; val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
        val count = runBlocking { f.base.journal.timeline().size }
        compose.setContent { app(f,nav,academic=c) }; compose.waitUntil(10000) { c.facts != null }
        compose.onNodeWithText("New Exam",substring=false).performClick(); compose.onNodeWithText("Title",substring=false).performTextInput("Missing semester")
        compose.onNodeWithText("Save",substring=false).performClick(); compose.waitUntil(10000) { c.feedback == AcademicFeedback.INVALID }
        compose.onNodeWithText("Missing semester").assertExists(); runBlocking { check(f.base.journal.timeline().size == count) }
        compose.onNodeWithText("Cancel",substring=false).performClick(); compose.onNodeWithText("Discard draft").performClick()
    }
    @Test fun actualAndroidD10CoreScreenshotCandidates() = fixture { f, context ->
        val nav = AndroidNavigation(); val core = CoreScreenCoordinator(); val c = AcademicScreenCoordinator(f.authoring,f.base.eventEditor)
        var dark by mutableStateOf(false)
        compose.setContent { key(dark) { app(f,nav,core,c,dark) } }
        val config = context.resources.configuration
        val cases = if (config.screenWidthDp == 360 && config.fontScale < 1.5f)
            listOf("today","calendar-day","calendar-week","calendar-month","tasks","courses","exams","course-detail","exam-detail","course-form","rule-form","semester-form","exam-form")
            else if (config.screenWidthDp == 840 || config.screenWidthDp == 1024) listOf("calendar-month","semester-form","course-detail","exam-detail") else listOf("calendar-month","semester-form")
        val out = File(context.getExternalFilesDir(null),"d10-02-screenshots").also { it.mkdirs() }
        for (theme in listOf(false,true)) for (case in cases) {
            compose.runOnIdle {
                c.closeDraft(); dark = theme
                core.mode = when (case) { "calendar-week" -> CalendarMode.WEEK; "calendar-month" -> CalendarMode.MONTH; else -> CalendarMode.DAY }
                nav.open(when { case == "today" -> AndroidDestination.TODAY; case.startsWith("calendar") -> AndroidDestination.CALENDAR; case == "tasks" -> AndroidDestination.TASKS; case == "exams" || case.startsWith("exam-") -> AndroidDestination.EXAMS; else -> AndroidDestination.COURSES })
            }
            val academic = nav.current in listOf(AndroidDestination.COURSES,AndroidDestination.EXAMS)
            compose.waitUntil(10000) { compose.onAllNodesWithTag(if (academic) "academic-ready" else "schedule-ready").fetchSemanticsNodes().isNotEmpty() && (!academic || c.facts != null) }
            if (case == "course-detail") compose.runOnIdle { c.selected = AcademicRecord.Subject(c.facts!!.courses.first()) }
            if (case == "exam-detail") compose.runOnIdle { c.selected = AcademicRecord.Assessment(c.facts!!.exams.first()) }
            if (case == "course-form") compose.runOnIdle { c.edit(AcademicRecord.Subject(c.facts!!.courses.first())) }
            if (case == "rule-form") compose.runOnIdle { c.edit(AcademicRecord.Rule(c.facts!!.courseScheduleRules.first())) }
            if (case == "semester-form") compose.runOnIdle { c.edit(AcademicRecord.Term(f.semester)) }
            if (case == "exam-form") compose.runOnIdle { c.edit(AcademicRecord.Assessment(c.facts!!.exams.first { it.schedule is dev.agenticscheduler.domain.academic.ExamSchedule.Exact })) }
            val expectedFormTitle = when (case) { "semester-form" -> "Edit Semester"; "exam-form" -> "Edit Exam"; "course-form" -> "Edit Course"; "rule-form" -> "Edit CourseScheduleRule"; else -> null }
            if (expectedFormTitle != null) compose.waitUntil(10000) { compose.onAllNodesWithText(expectedFormTitle,substring=false).fetchSemanticsNodes().isNotEmpty() }
            if (case.endsWith("-detail")) compose.waitUntil(10000) { compose.onAllNodesWithText("Close detail",substring=false).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val capture = if (expectedFormTitle != null) compose.onNode(isRoot() and hasAnyDescendant(hasText(expectedFormTitle,substring=false)) and hasAnyDescendant(hasSetTextAction())) else if (case.endsWith("-detail")) compose.onNode(isRoot() and hasAnyDescendant(hasText("Close detail",substring=false))) else compose.onAllNodes(isRoot()).onFirst()
            val file = File(out,"android-${config.screenWidthDp}x${config.screenHeightDp}-font${(config.fontScale*100).toInt()}-${if(theme) "dark" else "light"}-$case.png")
            // Compose PixelCopy can capture the Activity behind an Android Dialog.
            // Assert the intended semantic surface, then capture the real composed display.
            capture.assertExists()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            bitmap.recycle()
            check(f.base.providerRequests == 0)
        }
    }
    @Composable private fun app(f: D10CoreFixtureGraph,nav: AndroidNavigation,core: CoreScreenCoordinator = remember { CoreScreenCoordinator() },academic: AcademicScreenCoordinator? = null,dark: Boolean = false) {
        val b = f.base
        AndroidApp(b.reads,b.planner,b.profileSettings,b.eventEditor,b.taskEditor,b.agent,b.run,b.secrets,b.enrollments,b.ids,b.conversationSettings,null,{},
            remember { AndroidScheduleScreenCoordinator(b.reads,b.date,b.zone) },nav,dark,academicService=f.authoring,coreSession=core,academicSession=academic,presentationNow=f.now)
    }
    private fun primary(name: String) {
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        val node = compose.onNodeWithTag("android-nav-$name")
        if (config.fontScale > 1.5f && (config.screenWidthDp < 600 || config.screenHeightDp < 480)) node.performScrollTo()
        node.performClick()
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun fixture(test: (D10CoreFixtureGraph,Context) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("d10-core-", ".db",context.cacheDir)
        val isolated = object : ContextWrapper(context) { override fun getDatabasePath(name: String) = file }
        val db = openAndroidDatabase(isolated); val f = D10CoreFixtureGraph(db)
        try { runBlocking { f.seed() }; test(f,context) } finally { f.base.client.close(); db.close(); file.delete() }
    }
}
