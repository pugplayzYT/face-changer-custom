package com.pugplayz.facechanger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFrameRatePolicyTest {
    @Test fun prefersExposureHeadroomOverFixedSixty() {
        assertEquals(15..30, selectExposureFriendlyFps(listOf(60..60, 30..60, 30..30, 15..30), 60))
    }

    @Test fun allowsSixtyWhenExposureCanStillAdapt() {
        assertEquals(15..60, selectExposureFriendlyFps(listOf(15..30, 60..60, 15..60), 60))
    }

    @Test fun choosesWiderExposureRangeAtSameCeiling() {
        assertEquals(7..30, selectExposureFriendlyFps(listOf(15..30, 7..30), 60))
    }

    @Test fun leavesDefaultsWhenOnlyFixedOrHighSpeedRangesExist() {
        assertNull(selectExposureFriendlyFps(listOf(30..30, 60..60, 30..60, 15..120), 60))
        assertNull(selectExposureFriendlyFps(emptyList(), 60))
    }
}
