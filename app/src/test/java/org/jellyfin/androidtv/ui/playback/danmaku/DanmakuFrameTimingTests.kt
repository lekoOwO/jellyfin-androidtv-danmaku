package org.jellyfin.androidtv.ui.playback.danmaku

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DanmakuFrameTimingTests : StringSpec({
    "first frame and explicit configuration changes request a rebuild" {
        danmakuNeedsRebuild(0, Long.MIN_VALUE, false) shouldBe true
        danmakuNeedsRebuild(1000, 1000, true) shouldBe true
    }
    "paused playback and ordinary forward progress do not request a rebuild" {
        danmakuNeedsRebuild(1000, 1000, false) shouldBe false
        danmakuNeedsRebuild(1016, 1000, false) shouldBe false
    }
    "backward tolerance uses the original strict boundary" {
        danmakuNeedsRebuild(750, 1000, false) shouldBe false
        danmakuNeedsRebuild(749, 1000, false) shouldBe true
    }
    "forward jump threshold uses the original strict boundary" {
        danmakuNeedsRebuild(3000, 1000, false) shouldBe false
        danmakuNeedsRebuild(3001, 1000, false) shouldBe true
    }
    "comments are active at both display interval endpoints" {
        danmakuCommentIsActive(1000, 1000, 6000) shouldBe true
        danmakuCommentIsActive(1000, 7000, 6000) shouldBe true
    }
    "expired comments and comments in the future are inactive" {
        danmakuCommentIsActive(1000, 7001, 6000) shouldBe false
        danmakuCommentIsActive(1000, 999, 6000) shouldBe false
        danmakuCommentIsActive(-1000, 0, 6000) shouldBe true
    }
})
