package com.pugplayz.facechanger

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The user-facing performance setting is deliberately FPS-only.
 * It never changes landmark count, tracking model, script semantics, or analysis resolution.
 */
internal enum class FilterPerformance(
    val label: String,
    val targetFps: Int,
    val smoothingAlpha: Float
) {
    LOW("LOW", 15, 1.0f),
    MEDIUM("MEDIUM", 30, 0.68f),
    MAX("MAX", 60, 0.48f);

    fun next(): FilterPerformance = when (this) {
        LOW -> MEDIUM
        MEDIUM -> MAX
        MAX -> LOW
    }
}

/**
 * Monotonic frame gate used by ImageAnalysis. CameraX may deliver faster than the selected level;
 * frames that arrive before the next deadline are closed immediately instead of entering tracking
 * or script rendering.
 */
internal class FramePacer(initial: FilterPerformance = FilterPerformance.MAX) {
    private val selected = AtomicReference(initial)
    private val lastAcceptedNs = AtomicLong(Long.MIN_VALUE)

    val level: FilterPerformance
        get() = selected.get()

    fun set(level: FilterPerformance) {
        selected.set(level)
        // Allow the new rate to take effect immediately instead of inheriting the old deadline.
        lastAcceptedNs.set(Long.MIN_VALUE)
    }

    fun cycle(): FilterPerformance = level.next().also(::set)

    fun shouldProcess(nowNs: Long): Boolean {
        val intervalNs = 1_000_000_000L / level.targetFps.toLong()
        while (true) {
            val previous = lastAcceptedNs.get()
            if (previous != Long.MIN_VALUE && nowNs - previous < intervalNs) return false
            if (lastAcceptedNs.compareAndSet(previous, nowNs)) return true
        }
    }
}
