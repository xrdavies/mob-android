package com.xrdavies.mob.ui.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class MappingTest {
    // A 1000x500 view showing a 2000x1000 desktop: fitted at half size, no letterbox.
    private val view = IntSize(1000, 500)

    @Test
    fun mapsWithoutZoom() {
        assertEquals(IntOffset(0, 0), toDesktop(Offset(0f, 0f), view, 2000, 1000, 1f, Offset.Zero))
        assertEquals(IntOffset(1000, 500), toDesktop(Offset(500f, 250f), view, 2000, 1000, 1f, Offset.Zero))
    }

    @Test
    fun mapsWithZoomAndPan() {
        // 2x around the centre: the view centre still shows the desktop centre.
        assertEquals(IntOffset(1000, 500), toDesktop(Offset(500f, 250f), view, 2000, 1000, 2f, Offset.Zero))
        // The view's left edge now shows a quarter of the way in.
        assertEquals(IntOffset(500, 500), toDesktop(Offset(0f, 250f), view, 2000, 1000, 2f, Offset.Zero))
        // Panned right by 100 px on screen = 100 desktop px less at 2x on a half-size fit.
        assertEquals(IntOffset(900, 500), toDesktop(Offset(500f, 250f), view, 2000, 1000, 2f, Offset(100f, 0f)))
    }

    @Test
    fun clampsOutsideTheDesktop() {
        // A 4:3 desktop has bars left and right in the wide view; touches there clamp to its edge.
        assertEquals(IntOffset(0, 0), toDesktop(Offset(10f, 0f), view, 800, 600, 1f, Offset.Zero))
        assertEquals(IntOffset(799, 599), toDesktop(Offset(990f, 500f), view, 800, 600, 1f, Offset.Zero))
    }
}
