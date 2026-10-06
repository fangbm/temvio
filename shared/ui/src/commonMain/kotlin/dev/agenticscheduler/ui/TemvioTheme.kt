package dev.agenticscheduler.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** Presentation roles only. Theme cannot convey business success or permission. */
data class TemvioColors(
    val surface: Color, val container: Color, val elevated: Color, val selected: Color,
    val text: Color, val secondary: Color, val muted: Color,
    val border: Color, val strongBorder: Color, val focus: Color, val accent: Color,
    val success: Color, val warning: Color, val danger: Color, val conflict: Color,
    val event: Color, val task: Color, val course: Color, val exam: Color,
    val focusBlock: Color, val agent: Color,
)

val LightTemvioColors = TemvioColors(
    Color(0xFFF6F7F9), Color(0xFFEEF1F4), Color.White, Color(0xFFDCEDE9),
    Color(0xFF17232D), Color(0xFF465661), Color(0xFF576571),
    Color(0xFFD4DBE0), Color(0xFF74858F), Color(0xFF006B5D), Color(0xFF006B5D),
    Color(0xFF256342), Color(0xFF795200), Color(0xFFA82E36), Color(0xFF913F15),
    Color(0xFF285D94), Color(0xFF006B5D), Color(0xFF68548C), Color(0xFF8A481F),
    Color(0xFF4C6073), Color(0xFF006B5D),
)
val DarkTemvioColors = TemvioColors(
    Color(0xFF11191F), Color(0xFF1C252D), Color(0xFF202B34), Color(0xFF223E3B),
    Color(0xFFEAF0F4), Color(0xFFBBC8D0), Color(0xFFA3B2BD),
    Color(0xFF394750), Color(0xFF81959F), Color(0xFF81D8C4), Color(0xFF81D8C4),
    Color(0xFF95D4AB), Color(0xFFE5C384), Color(0xFFFFAAB0), Color(0xFFF4B894),
    Color(0xFFA0C8F7), Color(0xFF81D8C4), Color(0xFFC8B8ED), Color(0xFFF1B58F),
    Color(0xFFBACCDD), Color(0xFF81D8C4),
)
val LocalTemvioColors = staticCompositionLocalOf { LightTemvioColors }
object TemvioSpace {
    val small = 8.dp; val medium = 16.dp; val large = 24.dp; val section = 32.dp
    val readingWidth = 1040.dp; val formWidth = 640.dp; val target = 48.dp
}

@Composable
fun TemvioTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkTemvioColors else LightTemvioColors
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalTemvioColors provides c) {
        MaterialTheme(
            colorScheme = scheme.copy(
                primary = c.accent, onPrimary = if (dark) c.surface else Color.White,
                primaryContainer = c.selected, onPrimaryContainer = c.text,
                secondary = c.secondary, background = c.surface, onBackground = c.text,
                surface = c.elevated, onSurface = c.text, surfaceVariant = c.container,
                onSurfaceVariant = c.secondary, outline = c.strongBorder,
                error = c.danger, onError = if (dark) c.surface else Color.White,
            ),
            shapes = Shapes(RoundedCornerShape(4.dp), RoundedCornerShape(6.dp), RoundedCornerShape(10.dp), RoundedCornerShape(14.dp), RoundedCornerShape(18.dp)),
            typography = Typography(
                headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
                headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
                titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
                titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
                labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            ),
            content = content,
        )
    }
}
