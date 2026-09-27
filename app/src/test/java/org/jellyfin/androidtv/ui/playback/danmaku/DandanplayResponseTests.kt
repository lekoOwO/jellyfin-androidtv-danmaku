package org.jellyfin.androidtv.ui.playback.danmaku

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject

/** Exercise parser decisions with JSON-accessor doubles; no Android runtime or network is needed. */
class DandanplayResponseTests : StringSpec({
    "a missing search array produces no results" {
        DandanplayClient.parseSearchResponse(jsonObject()) shouldBe emptyList()
    }
    "non-objects and missing episode arrays are skipped but empty arrays remain valid" {
        val missing = jsonObject(numbers = mapOf("animeId" to 1L))
        val empty = jsonObject(numbers = mapOf("animeId" to 2L), arrays = mapOf("episodes" to jsonArray()))
        val result = DandanplayClient.parseSearchResponse(
            jsonObject(arrays = mapOf("animes" to jsonArray(null, missing, empty))),
        )
        result.map { it.animeId } shouldBe listOf(2L)
        result.single().episodes shouldBe emptyList()
    }
    "an invalid episode entry does not discard subsequent episodes" {
        val first = jsonObject(strings = mapOf("episodeTitle" to "第1話"), numbers = mapOf("episodeId" to 11L))
        val last = jsonObject(strings = mapOf("episodeTitle" to "第3話"), numbers = mapOf("episodeId" to 13L))
        val anime = jsonObject(
            strings = mapOf("animeTitle" to "Show", "type" to "tvseries", "typeDescription" to "TV"),
            numbers = mapOf("animeId" to 10L),
            arrays = mapOf("episodes" to jsonArray(first, null, last)),
        )
        val result = DandanplayClient.parseSearchResponse(jsonObject(arrays = mapOf("animes" to jsonArray(anime))))
        result.single().animeTitle shouldBe "Show"
        result.single().type shouldBe "tvseries"
        result.single().typeDescription shouldBe "TV"
        result.single().episodes.map { it.episodeId } shouldBe listOf(11L, 13L)
        result.single().episodes.map { it.episodeTitle } shouldBe listOf("第1話", "第3話")
    }
    "missing optional fields retain the existing accessor defaults" {
        val anime = jsonObject(arrays = mapOf("episodes" to jsonArray(jsonObject())))
        val result = DandanplayClient.parseSearchResponse(jsonObject(arrays = mapOf("animes" to jsonArray(anime))))
        result.single().animeId shouldBe 0L
        result.single().animeTitle shouldBe ""
        result.single().episodes.single().episodeId shouldBe 0L
        result.single().episodes.single().episodeTitle shouldBe ""
    }
    "comments keep order and sources while ignoring invalid entries" {
        val first = jsonObject(strings = mapOf("p" to "1,1,65280,[Gamer]user", "m" to "one"))
        val invalid = jsonObject(strings = mapOf("p" to "bad", "m" to "ignored"))
        val blank = jsonObject(strings = mapOf("p" to "2,1,0,user", "m" to " "))
        val last = jsonObject(strings = mapOf("p" to "3,6,16777215,[BiliBili]user", "m" to "two"))
        val result = DandanplayClient.parseCommentsResponse(
            jsonObject(arrays = mapOf("comments" to jsonArray(first, null, invalid, blank, last))),
        )
        result.map { it.text } shouldBe listOf("one", "two")
        result.map { it.source } shouldBe listOf(DanmakuSource.GAMER, DanmakuSource.BILIBILI)
        result.last().mode shouldBe DanmakuMode.SCROLL_LTR
    }
    "a missing comments array produces no comments" {
        DandanplayClient.parseCommentsResponse(jsonObject()) shouldBe emptyList()
    }
})

private fun jsonArray(vararg entries: JSONObject?): JSONArray = mockk {
    every { length() } returns entries.size
    every { optJSONObject(any()) } answers { entries.getOrNull(firstArg<Int>()) }
}

private fun jsonObject(
    strings: Map<String, String> = emptyMap(),
    numbers: Map<String, Long> = emptyMap(),
    arrays: Map<String, JSONArray> = emptyMap(),
): JSONObject = mockk {
    every { optString(any()) } answers { strings[firstArg<String>()].orEmpty() }
    every { optLong(any()) } answers { numbers[firstArg<String>()] ?: 0L }
    every { optJSONArray(any()) } answers { arrays[firstArg<String>()] }
}
