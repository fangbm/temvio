package dev.agenticscheduler.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agenticscheduler.ui.*

enum class DesktopDestination(val label: String) {
    TODAY("Today"), CALENDAR("Calendar"), TASKS("Tasks"), COURSES("Courses"), EXAMS("Exams"),
    PLANNER("Planner"), AGENT("Agent"), HISTORY("History"), INSIGHTS("Insights"), SETTINGS("Settings")
}

/** Session navigation only. No service or command is executed on a transition. */
class DesktopNavigation {
    // Every current destination is primary. Detail/form routes are a later typed graph.
    private var primary by mutableStateOf(DesktopDestination.TODAY)
    val current: DesktopDestination get() = primary
    val canGoBack: Boolean get() = false
    fun open(destination: DesktopDestination) { primary = destination }
    fun back(): Boolean = false
}

@Composable
internal fun DesktopAppShell(navigation: DesktopNavigation, dark: Boolean, onThemeChange: () -> Unit,
    detail: (@Composable () -> Unit)? = null,
    content: @Composable (DesktopDestination) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = LocalTemvioColors.current.surface) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = desktopLayout(maxWidth.value)
        val windowHeight = maxHeight
        Row(Modifier.fillMaxSize()) {
            if (layout != DesktopLayout.NARROW) {
                Surface(color = LocalTemvioColors.current.container) {
                    Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("temvio", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp))
                        Text("Your day, in focus", color = LocalTemvioColors.current.secondary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        DesktopDestination.entries.forEach { d ->
                            NavigationControl(d.label, navigation.current == d, { navigation.open(d) },
                                Modifier.fillMaxWidth().testTag("desktop-nav-${d.name}"))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (layout == DesktopLayout.NARROW) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Navigate") }
                            DropdownMenu(menu, { menu = false }) {
                                DesktopDestination.entries.forEach { d -> DropdownMenuItem(text = { Text(d.label) },
                                    onClick = { navigation.open(d); menu = false }, modifier = Modifier.testTag("desktop-nav-${d.name}")) }
                            }
                        }
                    }
                    Text(navigation.current.label, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.CenterVertically))
                    if (navigation.canGoBack) NavigationControl("Back", false, { navigation.back() })
                    NavigationControl("Open Agent", navigation.current == DesktopDestination.AGENT,
                        { navigation.open(DesktopDestination.AGENT) }, Modifier.testTag("global-agent"))
                    OutlinedButton(onClick = onThemeChange, modifier = Modifier.heightIn(min = 48.dp).testTag("theme-toggle")) { Text(if (dark) "Light theme" else "Dark theme") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = if (layout == DesktopLayout.WIDE) 1200.dp else TemvioSpace.readingWidth).fillMaxSize()) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { content(navigation.current) }
                            if (detail != null && layout == DesktopLayout.WIDE && windowHeight >= 480.dp)
                                Box(Modifier.width(360.dp).fillMaxHeight()) { detail() }
                        }
                    }
                }
            }
        }
    }
    }
}
