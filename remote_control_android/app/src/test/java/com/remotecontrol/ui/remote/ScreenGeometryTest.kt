package com.remotecontrol.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGeometryTest {

    @Test
    fun `a matching aspect ratio fills the stage`() {
        val box = ScreenGeometry.containRect(1920, 1080, 1920, 1080)

        assertEquals(ScreenRect(0f, 0f, 1920f, 1080f), box)
    }

    @Test
    fun `a wide frame is letterboxed top and bottom`() {
        // Stage is taller than the frame's aspect ratio.
        val box = ScreenGeometry.containRect(1000, 500, 1000, 1000)

        assertEquals(1000f, box.width, 0.01f)
        assertEquals(500f, box.height, 0.01f)
        assertEquals(250f, box.top, 0.01f)
        assertEquals(0f, box.left, 0.01f)
    }

    @Test
    fun `a tall frame is pillarboxed left and right`() {
        val box = ScreenGeometry.containRect(500, 1000, 1000, 1000)

        assertEquals(500f, box.width, 0.01f)
        assertEquals(1000f, box.height, 0.01f)
        assertEquals(250f, box.left, 0.01f)
        assertEquals(0f, box.top, 0.01f)
    }

    @Test
    fun `corners map to the laptop corners`() {
        val box = ScreenRect(100f, 50f, 800f, 600f)

        assertEquals(NormalizedPoint(0f, 0f), ScreenGeometry.normalize(100f, 50f, box))
        assertEquals(NormalizedPoint(1f, 1f), ScreenGeometry.normalize(900f, 650f, box))
    }

    @Test
    fun `the centre maps to the centre`() {
        val point = ScreenGeometry.normalize(500f, 350f, ScreenRect(100f, 50f, 800f, 600f))

        assertEquals(0.5f, point!!.x, 1e-6f)
        assertEquals(0.5f, point.y, 1e-6f)
    }

    @Test
    fun `letterbox touches are rejected instead of clamped`() {
        val box = ScreenGeometry.containRect(1000, 500, 1000, 1000) // top/bottom bars

        assertNull("above the frame", ScreenGeometry.normalize(500f, 10f, box))
        assertNull("below the frame", ScreenGeometry.normalize(500f, 900f, box))
        assertNull("on the top edge, above the frame", ScreenGeometry.normalize(500f, 249f, box))
        assertTrue(ScreenGeometry.normalize(500f, 251f, box) != null)
    }

    @Test
    fun `pillarbox touches are rejected`() {
        val box = ScreenGeometry.containRect(500, 1000, 1000, 1000) // left/right bars

        assertNull(ScreenGeometry.normalize(100f, 500f, box))
        assertNull(ScreenGeometry.normalize(900f, 500f, box))
        assertTrue(ScreenGeometry.normalize(250f, 500f, box) != null)
    }

    @Test
    fun `no frame means nothing is mappable`() {
        assertNull(ScreenGeometry.normalize(10f, 10f, ScreenGeometry.containRect(0, 0, 800, 600)))
        assertNull(ScreenGeometry.normalize(10f, 10f, ScreenGeometry.containRect(1920, 1080, 0, 0)))
    }
}
