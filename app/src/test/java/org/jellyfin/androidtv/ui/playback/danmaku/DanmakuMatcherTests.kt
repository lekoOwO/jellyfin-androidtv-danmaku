package org.jellyfin.androidtv.ui.playback.danmaku

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.jellyfin.androidtv.ui.playback.danmaku.DandanplayClient.AnimeResult
import org.jellyfin.androidtv.ui.playback.danmaku.DandanplayClient.EpisodeResult
import java.io.IOException

class DanmakuMatcherTests : StringSpec({
    "primary success does not fetch aliases or original metadata" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime())))
        fixture.match(request(savedAnime = 1L to "Alias"))?.episodeId shouldBe 1L
        fixture.calls shouldBe listOf("Show")
    }
    "remembered title is searched after an empty primary result" {
        val fixture = SearchFixture(mapOf("Alias" to listOf(anime())))
        fixture.match(request(savedAnime = 1L to "Alias"))?.episodeId shouldBe 1L
        fixture.calls shouldBe listOf("Show", "Alias")
    }
    "original title is fetched lazily after the remembered title failed" {
        val fixture = SearchFixture(mapOf("Original" to listOf(anime())), "Original")
        fixture.match(request(savedAnime = 1L to "Alias"))?.episodeId shouldBe 1L
        fixture.calls shouldBe listOf("Show", "Alias", "<metadata>", "Original")
    }
    "identical remembered and original titles are not queried twice" {
        val fixture = SearchFixture(emptyMap(), "Show")
        fixture.match(request(savedAnime = 1L to "Show")) shouldBe null
        fixture.calls shouldBe listOf("Show", "<metadata>")
    }
    "blank original titles are skipped" {
        val fixture = SearchFixture(emptyMap(), " ")
        fixture.match() shouldBe null
        fixture.calls shouldBe listOf("Show", "<metadata>")
    }
    "series-only search is the final fallback" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(title = "Show 2"))))
        fixture.match(request(name = "Show 2", season = 2))?.episodeId shouldBe 1L
        fixture.calls shouldBe listOf("Show 2", "<metadata>", "Show")
    }
    "ambiguous later-season results expand and retain remembered candidate identity" {
        val first = anime(id = 1, title = "Other")
        val second = anime(id = 2, title = "Another")
        val replacement = anime(id = 1, title = "Show 2", episodes = listOf(episode(9)))
        val fixture = SearchFixture(mapOf("Show 2" to listOf(first, second), "Show" to listOf(replacement)))
        fixture.match(request(name = "Show 2", season = 2, savedAnime = 1L to "Other"))?.episodeId shouldBe 1L
        fixture.calls shouldBe listOf("Show 2", "Show")
    }
    "a single result retains the legacy no-expansion behavior" {
        val fixture = SearchFixture(mapOf("Show 2" to listOf(anime(title = "Other"))))
        fixture.match(request(name = "Show 2", season = 2))?.episodeId shouldBe 1L
        fixture.calls shouldBe listOf("Show 2")
    }
    "an explicit season result avoids expansion and outranks ordinary candidates" {
        val season = anime(id = 2, title = "Other Season 2", episodes = listOf(episode(2, "第1話")))
        val fixture = SearchFixture(mapOf("Show 2" to listOf(anime(), season)))
        fixture.match(request(name = "Show 2", season = 2))?.episodeId shouldBe 2L
        fixture.calls shouldBe listOf("Show 2")
    }
    "a remembered anime ID takes precedence over season heuristics" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(), anime(id = 2, title = "Show Season 2"))))
        fixture.match(request(season = 2, savedAnime = 1L to "Show"))?.animeTitle shouldBe "Show"
    }
    "a missing remembered ID falls back to the normal ranking" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(), anime(id = 2, title = "Other"))))
        fixture.match(request(savedAnime = 99L to "Missing"))?.animeTitle shouldBe "Show"
    }
    "exact episode numbers win over list positions across missing episodes" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(episodes = listOf(episode(1), episode(4))))))
        fixture.match(request(episodeIndex = 4))?.episodeId shouldBe 4L
    }
    "specials are excluded when numbered main episodes exist" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(episodes = listOf(episode(9, "OVA"), episode(1))))))
        fixture.match()?.episodeId shouldBe 1L
    }
    "non-numbered episodes use positional fallback" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(episodes = listOf(episode(7, "A"), episode(8, "B"))))))
        fixture.match(request(episodeIndex = 2))?.episodeId shouldBe 8L
    }
    "continuous episode numbering can still match season-relative episode one" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(episodes = listOf(episode(13), episode(14))))))
        fixture.match(request(episodeIndex = 1))?.episodeId shouldBe 13L
        fixture.match(request(episodeIndex = 14))?.episodeId shouldBe 14L
    }
    "empty and out-of-range episode lists produce no match" {
        SearchFixture(mapOf("Show" to listOf(anime(episodes = emptyList())))).match() shouldBe null
        SearchFixture(mapOf("Show" to listOf(anime()))).match(request(episodeIndex = 20)) shouldBe null
    }
    "episode coverage bonus outranks title and TV-type bonuses" {
        val fixture = SearchFixture(mapOf("Show" to listOf(
            anime(episodes = listOf(episode(1))),
            anime(id = 2, title = "Other", type = "movie", episodes = listOf(episode(1), episode(2))),
        )))
        fixture.match(request(episodeIndex = 2))?.animeTitle shouldBe "Other"
    }
    "title matching bonus outranks TV-type bonus" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(title = "Other"), anime(id = 2, type = "movie"))))
        fixture.match()?.animeTitle shouldBe "Show"
    }
    "TV type and first-season preference retain their existing ranking" {
        val fixture = SearchFixture(mapOf("Show" to listOf(
            anime(id = 1, type = "movie"),
            anime(id = 2, episodes = listOf(episode(2, "第1話"))),
        )))
        fixture.match()?.episodeId shouldBe 2L
        val firstSeason = SearchFixture(mapOf("Show" to listOf(anime(title = "Show 2期"), anime(id = 2))))
        firstSeason.match()?.animeTitle shouldBe "Show"
    }
    "equal scores preserve the original search order" {
        val fixture = SearchFixture(mapOf("Show" to listOf(anime(episodes = listOf(episode(7, "第1話"))), anime(id = 2))))
        fixture.match()?.episodeId shouldBe 7L
    }
    "search failures and cancellation are not turned into empty matches" {
        val failure = IOException("offline")
        runCatching { DanmakuMatcher { throw failure }.findMatch(request()) { null } }.exceptionOrNull() shouldBe failure
        val cancelled = CancellationException("cancelled")
        runCatching { DanmakuMatcher { emptyList() }.findMatch(request()) { throw cancelled } }
            .exceptionOrNull() shouldBe cancelled
    }
})

private fun episode(id: Long, title: String = "第${id}話") = EpisodeResult(id, title)

private fun anime(
    id: Long = 1,
    title: String = "Show",
    type: String = "tvseries",
    episodes: List<EpisodeResult> = listOf(episode(1)),
) = AnimeResult(id, title, "", type, episodes)

private fun request(
    name: String = "Show",
    season: Int = 1,
    episodeIndex: Int = 1,
    savedAnime: Pair<Long, String>? = null,
) = DanmakuMatcher.Request(name, "Show", season, episodeIndex, savedAnime)

private class SearchFixture(private val responses: Map<String, List<AnimeResult>>, private val original: String? = null) {
    val calls = mutableListOf<String>()
    private val matcher = DanmakuMatcher { name ->
        calls.add(name)
        responses[name].orEmpty()
    }

    suspend fun match(request: DanmakuMatcher.Request = request()): SavedDanmakuMatch? = matcher.findMatch(request) {
        calls.add("<metadata>")
        original
    }
}
