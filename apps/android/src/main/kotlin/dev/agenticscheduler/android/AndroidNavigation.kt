package dev.agenticscheduler.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.agenticscheduler.ui.*

enum class AndroidDestination(val label: String) {
    TODAY("Today"), CALENDAR("Calendar"), TASKS("Tasks"), AGENT("Agent"), MORE("More"),
    COURSES("Courses"), EXAMS("Exams"), PLANNER("Planner"), HISTORY("History"), INSIGHTS("Insights"),
    SYNC_SECURITY("Sync / Security"), PROVIDER("Provider"), SETTINGS("Settings");
    companion object { val primary = listOf(TODAY, CALENDAR, TASKS, AGENT, MORE) }
}
class AndroidNavigation {
    private val stack = mutableStateListOf(AndroidDestination.TODAY)
    val current: AndroidDestination get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    fun open(destination: AndroidDestination) { if (current != destination) stack.add(destination) }
    fun back(): Boolean = if (canGoBack) { stack.removeAt(stack.lastIndex); true } else false
}

@Composable
internal fun AndroidAppShell(navigation: AndroidNavigation, dark: Boolean, onThemeChange: () -> Unit,
    detail: (@Composable () -> Unit)? = null,
    content: @Composable (AndroidDestination) -> Unit) {
    BackHandler(navigation.canGoBack) { navigation.back() }
    Surface(Modifier.fillMaxSize(), color = LocalTemvioColors.current.surface) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val layout = androidLayout(maxWidth.value, maxHeight.value)
        val windowWidth = maxWidth
        Row(Modifier.fillMaxSize()) {
            if (layout != AndroidLayout.COMPACT) {
                Surface(color = LocalTemvioColors.current.container) {
                    Column(Modifier.width(136.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("temvio", style = MaterialTheme.typography.titleLarge)
                        AndroidDestination.primary.forEach { d -> NavigationControl(d.label,
                            navigation.current == d || d == AndroidDestination.MORE && navigation.current !in AndroidDestination.primary,
                            { navigation.open(d) }, Modifier.fillMaxWidth().testTag("android-nav-${d.name}")) }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(navigation.current.label, style = MaterialTheme.typography.headlineMedium)
                    OutlinedButton(onClick = onThemeChange, modifier = Modifier.heightIn(min = 48.dp).testTag("theme-toggle")) { Text(if (dark) "Light theme" else "Dark theme") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = TemvioSpace.readingWidth).fillMaxSize()) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { content(navigation.current) }
                            if (detail != null && layout == AndroidLayout.EXPANDED && windowWidth >= 900.dp)
                                Box(Modifier.width(320.dp).fillMaxHeight()) { detail() }
                        }
                    }
                }
                if (layout == AndroidLayout.COMPACT) {
                    Surface(color = LocalTemvioColors.current.container) {
                        val largeText = LocalDensity.current.fontScale > 1.5f
                        Row(Modifier.fillMaxWidth().then(if (largeText) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            AndroidDestination.primary.forEach { d -> NavigationControl(d.label,
                                navigation.current == d || d == AndroidDestination.MORE && navigation.current !in AndroidDestination.primary,
                                { navigation.open(d) }, (if (largeText) Modifier.widthIn(min = 140.dp) else Modifier.weight(1f)).testTag("android-nav-${d.name}"), compact = true) }
                        }
                    }
                }
            }
        }
    }
    }
}
