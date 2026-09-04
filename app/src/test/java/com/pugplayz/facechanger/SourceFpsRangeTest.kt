package com.pugplayz.facechanger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceFpsRangeTest {
    @Test fun prefersAdaptive60OverFixed60() {
        assertEquals(SourceFpsRange(15, 60), chooseExposureFriendlyFps(
            listOf(SourceFpsRange(60, 60), SourceFpsRange(15, 30), SourceFpsRange(15, 60)), 60))
    }

    @Test fun prefersExposureHeadroomOverHighFrameRate() {
        assertEquals(SourceFpsRange(15, 30), chooseExposureFriendlyFps(
            listOf(SourceFpsRange(60, 60), SourceFpsRange(30, 60), SourceFpsRange(15, 30)), 60))
    }

    @Test fun neverRequestsHighSpeedOnlyRange() {
        assertEquals(SourceFpsRange(15, 30), chooseExposureFriendlyFps(
            listOf(SourceFpsRange(15, 120), SourceFpsRange(15, 30)), 60))
    }

    @Test fun prefersWiderExposureRangeAtSameCeiling() {
        assertEquals(SourceFpsRange(7, 30), chooseExposureFriendlyFps(
            listOf(SourceFpsRange(15, 30), SourceFpsRange(7, 30)), 60))
    }

    @Test fun keepsDeviceDefaultsWhenNoSuitableRangeExists() {
        assertNull(chooseExposureFriendlyFps(emptyList(), 60))
        assertNull(chooseExposureFriendlyFps(listOf(SourceFpsRange(60, 60)), 60))
        assertNull(chooseExposureFriendlyFps(listOf(SourceFpsRange(30, 60)), 60))
    }
}
