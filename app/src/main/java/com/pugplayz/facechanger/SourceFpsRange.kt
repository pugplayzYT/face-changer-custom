package com.pugplayz.facechanger

internal data class SourceFpsRange(val lower: Int, val upper: Int)

/** Preserve low-light exposure headroom before maximizing capture speed. */
internal fun chooseExposureFriendlyFps(
    supported: List<SourceFpsRange>,
    desiredFps: Int
): SourceFpsRange? = supported
    .filter {
        it.lower > 0 && it.lower <= 15 && it.lower < it.upper &&
            it.upper >= minOf(30, desiredFps) && it.upper <= desiredFps
    }
    .sortedWith(compareByDescending<SourceFpsRange> { it.upper }.thenBy { it.lower })
    .firstOrNull()
