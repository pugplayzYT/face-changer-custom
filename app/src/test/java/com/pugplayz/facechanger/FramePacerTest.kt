package com.pugplayz.facechanger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    @Test
    fun performanceLevelsArePureFpsTargets() {
        assertEquals(15, FilterPerformance.LOW.targetFps)
        assertEquals(30, FilterPerformance.MEDIUM.targetFps)
        assertEquals(60, FilterPerformance.MAX.targetFps)
    }

    @Test
    fun maxAcceptsAtSixtyFpsCadence() {
        val pacer = FramePacer(FilterPerformance.MAX)
        assertTrue(pacer.shouldProcess(0L))
        assertFalse(pacer.shouldProcess(10_000_000L))
        assertTrue(pacer.shouldProcess(16_666_667L))
    }

    @Test
    fun switchingLevelResetsDeadlineImmediately() {
        val pacer = FramePacer(FilterPerformance.LOW)
        assertTrue(pacer.shouldProcess(0L))
        assertFalse(pacer.shouldProcess(20_000_000L))
        pacer.set(FilterPerformance.MAX)
        assertTrue(pacer.shouldProcess(20_000_000L))
    }
}
