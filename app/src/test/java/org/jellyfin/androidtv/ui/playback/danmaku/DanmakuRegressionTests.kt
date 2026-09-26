package org.jellyfin.androidtv.ui.playback.danmaku

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DanmakuRegressionTests : StringSpec({
    "mode 6 preserves left to right scrolling" {
        DanmakuComment.fromDandanplay("1.5,6,16777215,[BiliBili]user", "hello")?.mode shouldBe DanmakuMode.SCROLL_LTR
    }
    "mode 1 preserves right to left scrolling" {
        DanmakuComment.fromDandanplay("1.5,1,16777215,user", "hello")?.mode shouldBe DanmakuMode.SCROLL
    }
    "top and bottom modes remain distinct" {
        DanmakuComment.fromDandanplay("1,5,0,user", "top")?.mode shouldBe DanmakuMode.TOP
        DanmakuComment.fromDandanplay("1,4,0,user", "bottom")?.mode shouldBe DanmakuMode.BOTTOM
    }
    "source metadata survives parsing" {
        val comment = DanmakuComment.fromDandanplay("12.5,1,65280,[Gamer]user", "text")!!
        comment.source shouldBe DanmakuSource.GAMER
        comment.timeSeconds shouldBe 12.5
        comment.colorRgb shouldBe 65280
    }
    "malformed and blank comments are ignored" {
        DanmakuComment.fromDandanplay("bad", "text") shouldBe null
        DanmakuComment.fromDandanplay("1,1,1,user", " ") shouldBe null
        DanmakuComment.fromDandanplay("1,7,1,user", "text") shouldBe null
    }
    "alpha is preserved when assigning an opaque RGB color" {
        (danmakuArgb(0xFFFFFFFF.toInt(), 128) ushr 24) shouldBe 128
        (danmakuArgb(0x12AB34, 128) and 0xFFFFFF) shouldBe 0x12AB34
    }
    "alpha boundaries are clamped" {
        (danmakuArgb(0xFFFFFF, -1) ushr 24) shouldBe 0
        (danmakuArgb(0xFFFFFF, 300) ushr 24) shouldBe 255
    }
    "LTR enters at the left and exits at the right" {
        danmakuScrollX(true, 1000f, 200f, 0f) shouldBe -200f
        danmakuScrollX(true, 1000f, 200f, 1f) shouldBe 1000f
    }
    "RTL enters at the right and exits at the left" {
        danmakuScrollX(false, 1000f, 200f, 0f) shouldBe 1000f
        danmakuScrollX(false, 1000f, 200f, 1f) shouldBe -200f
    }
    "opposite directions do not reuse an occupied scrolling lane" {
        danmakuScrollGapMs(200f, 200f, 1000f, 6000L, true) shouldBe 6000.0
        danmakuScrollGapMs(200f, 200f, 1000f, 6000L, false) shouldBe 1000.0
    }
    "legacy font weight fallback has a defined threshold" {
        FONT_BOLD_THRESHOLD shouldBe 600
    }
})
