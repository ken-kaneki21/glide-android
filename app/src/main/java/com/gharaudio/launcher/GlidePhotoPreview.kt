package com.gharaudio.launcher

import kotlin.math.roundToInt

/** A small comparison preview; export resolution is independent of this bound. */
internal fun photoPreviewDimensions(width: Int, height: Int, maxEdge: Int = 768): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0)
    val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
    return (width * scale).roundToInt().coerceIn(1, maxEdge) to
        (height * scale).roundToInt().coerceIn(1, maxEdge)
}
