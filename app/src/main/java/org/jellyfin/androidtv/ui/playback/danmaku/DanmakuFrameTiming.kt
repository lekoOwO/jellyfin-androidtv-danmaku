package org.jellyfin.androidtv.ui.playback.danmaku

/** Keep the first-frame sentinel out of arithmetic and preserve the existing seek thresholds. */
internal fun danmakuNeedsRebuild(positionMs: Long, previousMs: Long, forced: Boolean): Boolean {
    if (forced || previousMs == Long.MIN_VALUE) return true
    return positionMs < previousMs - BACKWARD_TOLERANCE_MS || positionMs > previousMs + FORWARD_JUMP_THRESHOLD_MS
}

/** A comment remains active at both endpoints of its display interval. */
internal fun danmakuCommentIsActive(timeMs: Long, positionMs: Long, durationMs: Long): Boolean =
    positionMs - timeMs <= durationMs && positionMs >= timeMs

private const val BACKWARD_TOLERANCE_MS = 250L
private const val FORWARD_JUMP_THRESHOLD_MS = 2000L
