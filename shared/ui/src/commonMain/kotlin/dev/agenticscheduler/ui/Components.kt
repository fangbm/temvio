package dev.agenticscheduler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class ActionRole { PRIMARY, SECONDARY, TERTIARY }
val ActionRoleKey = SemanticsPropertyKey<ActionRole>("TemvioActionRole")

@Composable
fun ActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    role: ActionRole = ActionRole.PRIMARY,
    content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val c = LocalTemvioColors.current
    val actionModifier = modifier.heightIn(min = TemvioSpace.target)
        .semantics { this[ActionRoleKey] = role }.onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, c.text, RoundedCornerShape(8.dp)) else Modifier)
    when (role) {
        ActionRole.PRIMARY -> Button(onClick = onClick, modifier = actionModifier, enabled = enabled,
            shape = RoundedCornerShape(8.dp), content = content)
        ActionRole.SECONDARY -> OutlinedButton(onClick = onClick, modifier = actionModifier, enabled = enabled,
            shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, c.strongBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent), content = content)
        ActionRole.TERTIARY -> TextButton(onClick = onClick, modifier = actionModifier, enabled = enabled,
            shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.textButtonColors(contentColor = c.secondary), content = content)
    }
}

@Composable
fun SectionHeading(title: String, detail: String? = null) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        detail?.let { Text(it, color = LocalTemvioColors.current.secondary, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun PresentationCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = LocalTemvioColors.current.elevated, shadowElevation = ElevationRole.FLAT.shadowElevation,
        border = BorderStroke(1.dp, LocalTemvioColors.current.border)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun StatusMessage(title: String, detail: String, modifier: Modifier = Modifier) {
    PresentationCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = LocalTemvioColors.current.secondary)
    }
}

@Composable
fun EntityBadge(kind: EntityKind) {
    val c = LocalTemvioColors.current
    val color = when (kind) {
        EntityKind.EVENT -> c.event; EntityKind.TASK -> c.task; EntityKind.COURSE -> c.course
        EntityKind.EXAM -> c.exam; EntityKind.FOCUS_BLOCK -> c.focusBlock; EntityKind.AGENT -> c.agent
    }
    Text(kind.label, color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun ScheduleCard(row: ScheduleRow, onEdit: (() -> Unit)? = null) {
    PresentationCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EntityBadge(row.kind)
            Text(row.time, style = MaterialTheme.typography.bodyMedium, color = LocalTemvioColors.current.secondary)
        }
        Text(row.title, style = MaterialTheme.typography.titleMedium)
        Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = LocalTemvioColors.current.secondary)
        onEdit?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit ${row.kind.label}") } }
    }
}

@Composable
fun ConversationCard(row: ConversationRow) {
    PresentationCard {
        Text(row.speaker, color = LocalTemvioColors.current.agent, fontWeight = FontWeight.SemiBold)
        Text(row.content, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ToolCard(row: ToolRow) {
    StatusMessage("Tool · ${row.name} · ${row.state}", row.result ?: "No structured result recorded.")
}

@Composable
fun DraftDiscardPrompt(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(onDismissRequest = onKeepEditing, title = { Text("Discard unsaved changes?") },
        text = { Text("Your draft has not been saved. Keep editing or explicitly discard it.") },
        confirmButton = { ActionButton(onClick = onDiscard) { Text("Discard draft") } },
        dismissButton = { ActionButton(onClick = onKeepEditing) { Text("Keep editing") } })
}

/** A rendering primitive, not a navigation store. Selection and intents come from the platform. */
@Composable
fun NavigationControl(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val c = LocalTemvioColors.current
    val outline = if (focused) c.focus else if (selected) c.strongBorder else c.border
    OutlinedButton(onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
            .semantics { this.selected = selected },
        shape = RoundedCornerShape(8.dp), border = BorderStroke(if (focused) 2.dp else 1.dp, outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) c.selected else c.surface,
            contentColor = c.text), contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 12.dp, vertical = 10.dp)) {
        Text(if (selected && !compact) "• $label" else label, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}
