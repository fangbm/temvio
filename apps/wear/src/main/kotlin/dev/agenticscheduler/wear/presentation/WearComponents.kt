package dev.agenticscheduler.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.*
import androidx.wear.compose.material3.*
import dev.agenticscheduler.ui.DarkTemvioColors
import dev.agenticscheduler.ui.LightTemvioColors
import dev.agenticscheduler.ui.TemvioSpace

/** Canonical semantic palette mapped to Wear components; no Android Material3 screens. */
@Composable
fun WearProductTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkTemvioColors else LightTemvioColors
    MaterialTheme(colorScheme = ColorScheme(
        primary = c.accent, onPrimary = if (dark) c.surface else Color.White,
        primaryContainer = c.selected, onPrimaryContainer = c.text,
        secondary = c.secondary, onSecondary = c.surface,
        secondaryContainer = c.container, onSecondaryContainer = c.text,
        background = c.surface, onBackground = c.text,
        surfaceContainer = c.container, surfaceContainerLow = c.surface, surfaceContainerHigh = c.elevated,
        onSurface = c.text, onSurfaceVariant = c.secondary, outline = c.strongBorder,
        error = c.danger, onError = if (dark) c.surface else Color.White,
    )) { AppScaffold(timeText = {}) { content() } }
}

/** Inscribed rectangle: every interactive bound fits the round face, including 200% text.
 * One native Wear lazy scroll container owns touch/rotary/focus. No drag-only scroll implementation.
 */
@Composable
fun WearPage(tag: String, modifier: Modifier = Modifier, content: TransformingLazyColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val state = rememberTransformingLazyColumnState()
        val verticalInset = maxHeight * .15f
        ScreenScaffold(scrollState = state, contentPadding = PaddingValues(horizontal = maxWidth * .15f, vertical = 4.dp), timeText = {}) { padding ->
            TransformingLazyColumn(state = state, contentPadding = padding,
                modifier = Modifier.fillMaxSize().padding(vertical = verticalInset).testTag(tag),
                verticalArrangement = Arrangement.spacedBy(TemvioSpace.small), content = content)
        }
    }
}

@Composable fun WearHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}
@Composable fun WearCopy(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall)
}
@Composable fun WearAction(label: String, tag: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = TemvioSpace.target).testTag(tag)
    if (primary) Button(onClick = onClick, modifier = modifier, enabled = enabled) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(label) }
}
