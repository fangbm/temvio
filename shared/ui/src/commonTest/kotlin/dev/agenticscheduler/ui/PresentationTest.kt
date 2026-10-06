package dev.agenticscheduler.ui

import kotlin.test.*
import kotlin.math.pow
import androidx.compose.ui.unit.dp

class PresentationTest {
    @Test fun responsiveBoundaries() {
        assertEquals(AndroidLayout.COMPACT, androidLayout(599f, 800f))
        assertEquals(AndroidLayout.MEDIUM, androidLayout(600f, 960f))
        assertEquals(AndroidLayout.MEDIUM, androidLayout(839f, 960f))
        assertEquals(AndroidLayout.EXPANDED, androidLayout(840f, 960f))
        assertEquals(AndroidLayout.COMPACT, androidLayout(800f, 360f))
        assertEquals(DesktopLayout.NARROW, desktopLayout(899f))
        assertEquals(DesktopLayout.STANDARD, desktopLayout(900f))
        assertEquals(DesktopLayout.STANDARD, desktopLayout(1439f))
        assertEquals(DesktopLayout.WIDE, desktopLayout(1440f))
    }
    @Test fun themeContrastAndEntityLabels() {
        fun luminance(c: androidx.compose.ui.graphics.Color): Double {
            fun channel(n: Float) = if (n <= .04045f) n / 12.92 else ((n + .055) / 1.055).pow(2.4)
            return .2126 * channel(c.red) + .7152 * channel(c.green) + .0722 * channel(c.blue)
        }
        fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
            val x=luminance(a); val y=luminance(b); return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
        }
        listOf(LightTemvioColors, DarkTemvioColors).forEach { c ->
            listOf(c.surface,c.container,c.elevated,c.selected).forEach { bg ->
                listOf(c.text,c.secondary,c.muted).forEach { assertTrue(contrast(it,bg)>=4.5, "Text contrast $it on $bg") }
                assertTrue(contrast(c.focus,bg)>=3, "Focus contrast")
                assertTrue(contrast(c.strongBorder,bg)>=3, "Secondary control outline contrast")
                assertTrue(contrast(c.accent,bg)>=4.5, "Secondary action text contrast")
            }
            listOf(c.event,c.task,c.course,c.exam,c.focusBlock,c.agent,c.success,c.warning,c.danger,c.conflict).forEach {
                assertTrue(contrast(it,c.elevated)>=4.5, "Semantic label contrast")
            }
        }
        assertEquals(6, EntityKind.entries.map { it.label }.toSet().size)
        assertEquals("FocusBlock", EntityKind.FOCUS_BLOCK.label)
    }
    @Test fun restrainedElevationAndMotionRolesHaveExplicitNoMotionOverride() {
        assertEquals(listOf(0.dp,2.dp,6.dp),ElevationRole.entries.map { it.shadowElevation })
        assertEquals(listOf(0,120,200),MotionRole.entries.map { it.durationMillis() })
        MotionRole.entries.forEach { assertEquals(0,it.durationMillis(noMotion=true)) }
        assertEquals(3,ActionRole.entries.size)
    }
}
