package org.jellyfin.androidtv.ui.playback.danmaku

import kotlin.math.max

/** Weight fallback for Android versions before API 28. */
internal const val FONT_BOLD_THRESHOLD = 600

/** Compose alpha and RGB together: Paint.setColor replaces the previous alpha. */
internal fun danmakuArgb(rgb: Int, alpha: Int): Int =
    (rgb and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24)

internal fun danmakuScrollX(
    leftToRight: Boolean,
    viewportWidth: Float,
    textWidth: Float,
    progress: Float,
): Float = if (leftToRight) {
    -textWidth + progress * (viewportWidth + textWidth)
} else {
    viewportWidth - progress * (viewportWidth + textWidth)
}

/** Opposite directions may only reuse a lane once the previous comment has left. */
internal fun danmakuScrollGapMs(
    previousWidth: Float,
    nextWidth: Float,
    viewportWidth: Float,
    durationMs: Long,
    oppositeDirection: Boolean,
): Double = if (oppositeDirection) {
    durationMs.toDouble()
} else {
    durationMs * max(
        previousWidth.toDouble() / (viewportWidth + previousWidth),
        nextWidth.toDouble() / (viewportWidth + nextWidth),
    )
}
