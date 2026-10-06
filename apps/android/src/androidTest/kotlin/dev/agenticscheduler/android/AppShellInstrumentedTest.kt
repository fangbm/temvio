package dev.agenticscheduler.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.fixtures.D10FixtureGraph
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppShellInstrumentedTest {
    @get:Rule val compose=createComposeRule()

    @Test fun typedBackStackDoesNotExecuteAnIntent() {
        val nav=AndroidNavigation()
        check(!nav.canGoBack)
        nav.open(AndroidDestination.MORE); nav.open(AndroidDestination.PROVIDER)
        check(nav.back()); check(nav.current==AndroidDestination.MORE)
        check(nav.back()); check(nav.current==AndroidDestination.TODAY)
        check(!nav.back())
    }

    @Test fun agentDraftSurvivesNavigationAndThemeWithoutSending() = fixture { f, _ ->
        val nav=AndroidNavigation()
        compose.setContent { AndroidApp(f.reads,f.planner,f.profileSettings,f.eventEditor,f.taskEditor,
            f.agent,f.run,f.secrets,f.enrollments,f.ids,f.conversationSettings,null,{},
            AndroidScheduleScreenCoordinator(f.reads,f.date,f.zone),nav,false) }
        openPrimary("AGENT")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Command").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Command").performTextInput("Keep this unsent draft")
        openPrimary("CALENDAR")
        compose.onNodeWithTag("theme-toggle").performClick()
        openPrimary("AGENT")
        compose.onNodeWithText("Keep this unsent draft").assertExists()
        compose.onNodeWithTag("android-nav-AGENT").assertIsSelected()
        check(f.providerRequests==0)
    }

    private fun openPrimary(name: String) {
        val config=InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        val node=compose.onNodeWithTag("android-nav-$name")
        // Large-font compact navigation intentionally scrolls, just like a user's gesture.
        if (config.fontScale > 1.5f && (config.screenWidthDp < 600 || config.screenHeightDp < 480)) node.performScrollTo()
        node.performClick()
    }

    @Test fun actualAndroidComposeScreenshotCandidates() = fixture { f, context ->
        val nav=AndroidNavigation()
        var dark by mutableStateOf(false)
        compose.setContent { key(dark) {
            AndroidApp(f.reads,f.planner,f.profileSettings,f.eventEditor,f.taskEditor,
                f.agent,f.run,f.secrets,f.enrollments,f.ids,f.conversationSettings,null,{},
                AndroidScheduleScreenCoordinator(f.reads,f.date,f.zone),nav,dark)
        } }
        val out=File(context.getExternalFilesDir(null),"d10-01-screenshots").also { it.mkdirs() }
        for (theme in listOf(false,true)) {
            compose.runOnIdle { dark=theme }
            for (destination in listOf(AndroidDestination.TODAY,AndroidDestination.CALENDAR,AndroidDestination.AGENT)) {
                compose.runOnIdle { nav.open(destination) }
                compose.waitUntil(10000) { compose.onAllNodesWithTag(if(destination==AndroidDestination.AGENT) "agent-ready" else "schedule-ready").fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                val config=context.resources.configuration
                val file=File(out,"android-${config.screenWidthDp}x${config.screenHeightDp}-font${(config.fontScale*100).toInt()}-${if(theme) "dark" else "light"}-${destination.name.lowercase()}.png")
                file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
                check(f.providerRequests==0)
            }
        }
    }

    private fun fixture(test: (D10FixtureGraph,Context)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("d10-ui-", ".db", context.cacheDir)
        val isolated=object : ContextWrapper(context) { override fun getDatabasePath(name: String)=file }
        val db=openAndroidDatabase(isolated)
        val f=D10FixtureGraph(db)
        try { runBlocking { f.seed() }; test(f,context) } finally { f.client.close(); db.close(); file.delete() }
    }
}
