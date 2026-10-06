package dev.agenticscheduler.ui

/** Window classes express available dp, never device identity. */
enum class AndroidLayout { COMPACT, MEDIUM, EXPANDED }
enum class DesktopLayout { NARROW, STANDARD, WIDE }
fun androidLayout(width: Float, height: Float) = when {
    height < 480f || width < 600f -> AndroidLayout.COMPACT
    width < 840f -> AndroidLayout.MEDIUM
    else -> AndroidLayout.EXPANDED
}
fun desktopLayout(width: Float) = when {
    width < 900f -> DesktopLayout.NARROW
    width < 1440f -> DesktopLayout.STANDARD
    else -> DesktopLayout.WIDE
}

enum class EntityKind(val label: String) {
    EVENT("Event"), TASK("Task"), COURSE("Course"), EXAM("Exam"), FOCUS_BLOCK("FocusBlock"), AGENT("Agent")
}
data class ScheduleRow(val id: String, val title: String, val kind: EntityKind, val time: String, val detail: String)
data class ConversationRow(val id: String, val speaker: String, val content: String)
data class ToolRow(val id: String, val name: String, val state: String, val result: String?)
