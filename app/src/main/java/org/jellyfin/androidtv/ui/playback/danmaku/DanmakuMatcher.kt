package org.jellyfin.androidtv.ui.playback.danmaku

import org.jellyfin.androidtv.ui.playback.danmaku.DandanplayClient.AnimeResult
import org.jellyfin.androidtv.ui.playback.danmaku.DandanplayClient.EpisodeResult

/** Matching policy without Android UI or preference dependencies. Lower scores are preferred. */
internal class DanmakuMatcher(private val search: suspend (String) -> List<AnimeResult>) {
    data class Request(
        val animeName: String,
        val seriesName: String?,
        val season: Int,
        val episodeIndex: Int,
        val savedAnime: Pair<Long, String>?,
    )

    suspend fun findMatch(request: Request, originalTitle: suspend () -> String?): SavedDanmakuMatch? {
        val results = searchCandidates(request, originalTitle)
        val anime = selectAnime(results, request) ?: return null
        val episode = selectEpisode(anime.episodes, request.episodeIndex) ?: return null
        return SavedDanmakuMatch(episode.episodeId, anime.animeTitle, episode.episodeTitle)
    }

    private suspend fun searchCandidates(request: Request, originalTitle: suspend () -> String?): List<AnimeResult> {
        var results = search(request.animeName)
        if (results.isEmpty()) results = searchSavedTitle(request)
        if (results.isEmpty()) results = searchOriginalTitle(request.animeName, originalTitle)
        return if (needsSeriesFallback(results, request.season)) expandSeriesSearch(results, request) else results
    }

    private suspend fun searchSavedTitle(request: Request): List<AnimeResult> {
        val title = request.savedAnime?.second ?: return emptyList()
        return if (title != request.animeName) search(title) else emptyList()
    }

    private suspend fun searchOriginalTitle(animeName: String, originalTitle: suspend () -> String?): List<AnimeResult> {
        // Fetch metadata lazily, after both ordinary and remembered-title searches failed.
        val title = originalTitle()
        return if (!title.isNullOrBlank() && title != animeName) search(title) else emptyList()
    }

    private fun needsSeriesFallback(results: List<AnimeResult>, season: Int): Boolean {
        if (results.isEmpty()) return true
        if (results.size == 1 || season <= 1) return false
        return results.none { matchesSeason(it.animeTitle, season) }
    }

    private suspend fun expandSeriesSearch(results: List<AnimeResult>, request: Request): List<AnimeResult> {
        val title = request.seriesName
        if (title.isNullOrBlank() || title == request.animeName) return results
        val fallback = search(title)
        // Preserve ordering, including duplicate results if the fallback returned nothing.
        return if (fallback.isEmpty()) results else (results + fallback).distinctBy(AnimeResult::animeId)
    }

    private fun selectAnime(results: List<AnimeResult>, request: Request): AnimeResult? {
        val remembered = results.firstOrNull { it.animeId == request.savedAnime?.first }
        if (remembered != null) return remembered
        if (results.size == 1) return results.first()
        if (request.season > 1) {
            val seasonMatch = results.firstOrNull { matchesSeason(it.animeTitle, request.season) }
            if (seasonMatch != null) return seasonMatch
        }
        // minByOrNull preserves the first result when scores tie, like the previous implementation.
        return results.minByOrNull { scoreAnime(it, request) }
    }

    private fun scoreAnime(anime: AnimeResult, request: Request): Int {
        var score = BASE_SCORE
        if (coversEpisode(anime.episodes, request.episodeIndex)) score -= EPISODE_COVERAGE_BONUS
        if (anime.type == "tvseries") score -= TV_SERIES_BONUS
        if (request.season == 1 && !SEASON_INDICATOR_REGEX.containsMatchIn(anime.animeTitle)) score -= FIRST_SEASON_BONUS
        val titleMatches = anime.animeTitle.contains(request.animeName, ignoreCase = true) ||
            request.animeName.contains(anime.animeTitle, ignoreCase = true)
        if (titleMatches) score -= TITLE_MATCH_BONUS
        return score + anime.animeTitle.length
    }

    private fun coversEpisode(episodes: List<EpisodeResult>, episodeIndex: Int): Boolean {
        val mainEpisodes = mainEpisodes(episodes)
        val initial = firstEpisodeNumber(mainEpisodes)
        // Retain the original ranking heuristic. Choosing an episode below still prefers its exact number.
        return episodeIndex in initial..(initial + mainEpisodes.size - 1)
    }

    private fun selectEpisode(episodes: List<EpisodeResult>, episodeIndex: Int): EpisodeResult? {
        val mainEpisodes = mainEpisodes(episodes)
        val exact = mainEpisodes.firstOrNull { episodeNumber(it.episodeTitle) == episodeIndex }
        if (exact != null) return exact
        val initial = firstEpisodeNumber(mainEpisodes)
        val index = if (episodeIndex < initial) episodeIndex - 1 else episodeIndex - initial
        return mainEpisodes.getOrNull(index)
    }

    private fun mainEpisodes(episodes: List<EpisodeResult>): List<EpisodeResult> {
        val standard = episodes.filter { EPISODE_NUMBER_REGEX.containsMatchIn(it.episodeTitle) }
        return standard.ifEmpty { episodes }
    }

    private fun firstEpisodeNumber(episodes: List<EpisodeResult>): Int {
        val first = episodes.firstOrNull { EPISODE_NUMBER_REGEX.containsMatchIn(it.episodeTitle) }
        return first?.let { episodeNumber(it.episodeTitle) } ?: 1
    }

    private fun episodeNumber(title: String): Int? =
        EPISODE_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun matchesSeason(title: String, season: Int): Boolean {
        val patterns = listOf(
            "第${season}季", "第 ${season} 季", "${season}期", "第${season}期",
            "Season $season", " $season",
        )
        return patterns.any { title.contains(it, ignoreCase = true) }
    }

    companion object {
        private const val BASE_SCORE = 100_000
        private const val EPISODE_COVERAGE_BONUS = 50_000
        private const val TV_SERIES_BONUS = 10_000
        private const val FIRST_SEASON_BONUS = 5_000
        private const val TITLE_MATCH_BONUS = 20_000
        private val EPISODE_NUMBER_REGEX = Regex("""第\s*(\d+)\s*[话話集]""")
        private val SEASON_INDICATOR_REGEX = Regex("""第\s*\d+\s*季|\d+期|Season\s+\d+""", RegexOption.IGNORE_CASE)
    }
}
