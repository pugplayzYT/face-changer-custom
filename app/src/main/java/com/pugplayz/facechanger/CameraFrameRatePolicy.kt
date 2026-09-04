package com.pugplayz.facechanger

/** Preserve at least 1/15 s of exposure headroom while allowing faster capture in good light. */
internal fun selectExposureFriendlyFps(supported: List<IntRange>, desiredFps: Int): IntRange? =
    supported
        .filter { it.first in 1..15 && it.last > it.first && it.last <= desiredFps }
        .sortedWith(compareByDescending<IntRange> { it.last }.thenBy { it.first })
        .firstOrNull()
