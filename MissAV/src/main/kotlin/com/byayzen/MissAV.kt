package com.byayzen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// -------------------------------------------------------
//  MissAV – CloudStream Provider
//  Modified: Auto-translate title & plot → Tiếng Việt
//  Uses Google Translate (free, no API key needed)
// -------------------------------------------------------

class MissAV : MainAPI() {
    override var mainUrl = "https://missav.live"
    override var name = "MissAV"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"   // Set language to Vietnamese

    // ── Google Translate helper (free endpoint) ──────────
    private suspend fun translateToVietnamese(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return try {
            val encoded = java.net.URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=vi&dt=t&q=$encoded"
            val resp = app.get(url).text
            // Parse result: [[["translated","original",...],...],...]
            val regex = Regex("\"([^\"]+)\"")
            val matches = regex.findAll(resp).map { it.groupValues[1] }.toList()
            // First match after the opening brackets is translated text
            if (matches.isNotEmpty()) matches[0] else text
        } catch (e: Exception) {
            text  // fallback: return original if translate fails
        }
    }

    // ── Main page categories ─────────────────────────────
    override val mainPage = mainPageOf(
        "/en/new?sort=published_at"       to "Mới nhất",
        "/dm169/en/weekly-hot?sort=weekly_views" to "Hot tuần",
        "/dm263/en/monthly-hot?sort=views" to "Hot tháng",
        "/en/english-subtitle"             to "Phụ đề tiếng Anh",
        "/dm628/en/uncensored-leak"        to "Không kiểm duyệt",
        "/dm29/en/tokyohot"                to "Tokyo Hot",
        "/dm150/en/fc2"                    to "FC2",
        "/dm136/en/gachinco"               to "Gachinco",
        "/dm105/en/heyzo"                  to "HEYZO",
        "/dm2469695/en/1pondo"             to "1Pondo",
        "/dm3959622/en/caribbeancom"       to "Caribbeancom",
        "/dm48032/en/caribbeancompr"       to "Caribbeancom Premium",
        "/dm3710098/en/10musume"           to "10musume",
        "/dm1198483/en/heyzo"              to "HEYZO Alt",
        "/dm1342558/en/pacopacomama"       to "Pacopacomama",
        "/dm29/en/xxxav"                   to "XXX AV",
        "/dm35/en/madou"                   to "Madou",
        "/dm20/en/naughty4610"             to "Naughty4610",
        "/dm22/en/naughty0930"             to "Naughty0930",
        "/dm24/en/marriedslash"            to "MarriedSlash",
        "/en/klive"                        to "K-Live",
        "/en/clive"                        to "C-Live"
    )

    // ── Parse a search result card ────────────────────────
    private fun Element.toSearchResult(): SearchResponse? {
        val url = this.selectFirst("a[href*='/en/'], a[href*='/dm']")
            ?.attr("href") ?: return null
        val title = this.selectFirst("div.my-2 a, div.title a, a.text-secondary")
            ?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        return newMovieSearchResponse(title, fixUrl(url), TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ── Fetch a main page section ─────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}${if (page > 1) "?page=$page" else ""}"
        val doc = app.get(url).document
        val items = doc.select("div.grid.grid-cols-2 > div, div.thumbnail.group")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ── Search ────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/en/search/$encoded").document
        return doc.select("div.grid.grid-cols-2 > div, div.thumbnail.group")
            .mapNotNull { it.toSearchResult() }
    }

    // ── Load detail page ──────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // --- Title ---
        val rawTitle = doc.selectFirst("h1.text-base")?.text()
            ?: doc.selectFirst("title")?.text() ?: return null
        val title = translateToVietnamese(rawTitle) ?: rawTitle

        // --- Plot (description/overview) ---
        // MissAV puts description in meta og:description or a specific div
        val rawPlot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst("p.mt-4, div.description, div.overview")?.text()
        val plot = translateToVietnamese(rawPlot)

        // --- Poster ---
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")

        // --- Tags / genres ---
        val tags = doc.select("div.text-secondary:contains(genre) a")
            .map { it.text() }

        // --- Actresses ---
        val actresses = doc.select("div.text-secondary:contains(actress) a")
            .map { Actor(it.text()) }

        // --- Year ---
        val year = doc.selectFirst("time, span.year")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.value?.toIntOrNull() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.actors = actresses
            this.year = year
        }
    }

    // ── Load video links ──────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val playlistId = Regex("/([a-f0-9\\-]{36})/")
            .find(doc.outerHtml())?.groupValues?.get(1) ?: return false

        val m3u8 = "https://surrit.com/$playlistId/playlist.m3u8"
        loadExtractor(m3u8, data, subtitleCallback, callback)

        // Also try English subs if available
        doc.select("track[kind=subtitles]").forEach { track ->
            val src = track.attr("src")
            val label = track.attr("label").ifEmpty { "Phụ đề" }
            if (src.isNotBlank()) subtitleCallback(SubtitleFile(label, src))
        }
        return true
    }
}
