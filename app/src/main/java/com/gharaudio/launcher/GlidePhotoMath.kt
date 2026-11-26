package com.gharaudio.launcher

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure pixel math: independent of Android so it can be checked on the host. */
internal fun glidePhotoTone(channel: Int): Int {
    val x = channel.coerceIn(0, 255) / 255.0
    return (255.0 * (x + 0.38 * x * (1.0 - x) * (1.0 - x)) + 0.5).toInt()
}

internal fun photoLuminance(pixel: Int): Int =
    (54 * (pixel ushr 16 and 255) + 183 * (pixel ushr 8 and 255) + 19 * (pixel and 255)) ushr 8

/** Lift dark scenes only. This is a heuristic, not a learned exposure estimator. */
internal fun photoLiftStrength(luminance: IntArray): Double {
    require(luminance.isNotEmpty())
    val histogram = IntArray(256)
    luminance.forEach { histogram[it.coerceIn(0, 255)]++ }
    var count = 0
    for (level in histogram.indices) {
        count += histogram[level]
        if (count > luminance.size / 2) return ((128 - level) / 96.0).coerceIn(0.0, 1.0)
    }
    return 0.0
}

/** A shared RGB gain preserves ratios up to integer rounding; headroom prevents clipping. */
internal fun photoLiftGains(strength: Double): DoubleArray = DoubleArray(256) { y ->
    if (y == 0) 1.0 else 1.0 + (glidePhotoTone(y) - y) * strength.coerceIn(0.0, 1.0) / y
}

internal fun liftPhotoPixel(pixel: Int, strength: Double): Int = liftPhotoPixel(pixel, photoLiftGains(strength))

internal fun liftPhotoPixel(pixel: Int, gains: DoubleArray): Int {
    val r = pixel ushr 16 and 255
    val g = pixel ushr 8 and 255
    val b = pixel and 255
    val y = photoLuminance(pixel)
    val peak = maxOf(r, g, b)
    if (y == 0 || peak == 0) return pixel
    val gain = minOf(gains[y], 255.0 / peak)
    return (pixel and -0x1000000) or ((r * gain).roundToInt().coerceIn(0, 255) shl 16) or
        ((g * gain).roundToInt().coerceIn(0, 255) shl 8) or (b * gain).roundToInt().coerceIn(0, 255)
}

internal fun medianOfThree(a: Int, b: Int, c: Int): Int =
    a + b + c - minOf(a, b, c) - maxOf(a, b, c)

/** Same exhaustive gradient objective and tie-break as the original; reuse gradients and prune losers. */
internal fun findFrameOffset(reference: IntArray, frame: IntArray, width: Int, height: Int,
    checkCancelled: () -> Unit = {}): Pair<Int, Int> {
    require(width >= 3 && height >= 3 && reference.size == width * height && frame.size == reference.size)
    val radius = minOf(8, width / 5, height / 5)
    val rx = IntArray(reference.size)
    val ry = IntArray(reference.size)
    val fx = IntArray(frame.size)
    val fy = IntArray(frame.size)
    for (y in 0 until height - 1) {
        checkCancelled()
        for (x in 0 until width - 1) {
            val i = y * width + x
            rx[i] = reference[i + 1] - reference[i]
            ry[i] = reference[i + width] - reference[i]
            fx[i] = frame[i + 1] - frame[i]
            fy[i] = frame[i + width] - frame[i]
        }
    }
    var best = 0 to 0
    var bestError = Long.MAX_VALUE
    for (dy in -radius..radius) for (dx in -radius..radius) {
        checkCancelled()
        var error = 0L
        rows@ for (y in radius until height - radius - 1) {
            var i = y * width + radius
            var j = (y + dy) * width + radius + dx
            repeat(width - 2 * radius - 1) {
                error += abs(rx[i] - fx[j]) + abs(ry[i] - fy[j])
                i++; j++
            }
            if (error > bestError) break@rows
        }
        if (error < bestError || error == bestError &&
            dx * dx + dy * dy < best.first * best.first + best.second * best.second) {
            bestError = error
            best = dx to dy
        }
    }
    return best
}

/** Reject ambiguous flat scenes, edge-of-search solutions, or substantial residual motion.
 * Thresholds are conservative fixture-tested heuristics; real-scene calibration remains required. */
internal fun safeToStack(reference: IntArray, frame: IntArray, width: Int, height: Int,
    offset: Pair<Int, Int>): Boolean {
    require(reference.size == width * height && frame.size == reference.size)
    val (dx, dy) = offset
    val radius = minOf(8, width / 5, height / 5)
    if (radius == 0 || abs(dx) >= radius || abs(dy) >= radius) return false
    val residuals = ArrayList<Int>()
    var texture = 0L
    for (y in radius until height - radius - 1) for (x in radius until width - radius - 1) {
        val i = y * width + x
        texture += abs(reference[i + 1] - reference[i]) + abs(reference[i + width] - reference[i])
        residuals.add(frame[(y + dy) * width + x + dx] - reference[i])
    }
    if (residuals.isEmpty() || texture.toDouble() / residuals.size < 3.0) return false
    val exposure = residuals.sorted()[residuals.size / 2]
    return residuals.count { abs(it - exposure) > 24 }.toDouble() / residuals.size <= 0.15
}
