package com.apoorvdarshan.calorietracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureLensTest {
    @Test
    fun backCameraIsPreferredWhenPresent() {
        assertEquals(CaptureLens.BACK, preferredCaptureLens(hasBack = true, hasFront = true))
        assertEquals(CaptureLens.BACK, preferredCaptureLens(hasBack = true, hasFront = false))
    }

    @Test
    fun frontOnlyDeviceUsesFrontCamera() {
        assertEquals(CaptureLens.FRONT, preferredCaptureLens(hasBack = false, hasFront = true))
    }

    @Test
    fun noCameraYieldsNoLens() {
        assertNull(preferredCaptureLens(hasBack = false, hasFront = false))
    }
}
