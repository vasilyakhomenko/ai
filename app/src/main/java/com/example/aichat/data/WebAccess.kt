package com.example.aichat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Прямой доступ в интернет (аналог web-плагина Claude Code).
 * Модель пишет маркеры в ответе — приложение выполняет и дописывает результат.
 *
 * Маркеры:
 *   [[fetch:https://example.com]]
 *   [[web:https://example.com]]
 *   [[search:запрос на английском или русском]]
 */
object WebAccess {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val fetchRegex = Regex(
        """\[\[(?:fetch|web):(https?://[^\]]+)\]\]""",
        RegexOption.IGNORE_CASE
    )
    private val searchRegex = Regex(
        """\[\[search:([^\]]+)\]\]""",
        RegexOption.IGNORE_CASE
    )

    val SYSTEM_HINT = """
## Internet access (built-in)
You can fetch live web pages and search the internet. When you need current information, put ONE marker on its own line in your reply (the app will fetch and append the result):

[[fetch:https://full-url-here]]
[[search:your search query]]

Rules:
- Prefer [[search:...]] for questions about news, facts, prices, docs.
- Prefer [[fetch:https://...]] when you already know the exact URL.
- Do not invent page contents — use the markers and wait for the tool result in the conversation.
- You may use at most 2 markers per reply.
""".trimIndent()

    suspend fun processMarkers(assistantText: String): String? {
        val results = mutableListOf<String>()
        var count = 0
        val max = 2

        for (m in fetchRegex.findAll(assistantText)) {
            if (count >= max) break
            val url = m.groupValues[1].trim()
            results.add(fetchUrl(url))
            count++
        }
        for (m in searchRegex.findAll(assistantText)) {
            if (count >= max) break
            val q = m.groupValues[1].trim()
            results.add(search(q))
            count++
        }
        if (results.isEmpty()) return null
        return results.joinToString("\n\n")
    }

    private suspend fun fetchUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; AIChat/1.0; +mobile)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val text = htmlToText(body).take(6000)
                "› web fetch ${resp.code} $url\n```\n$text\n```"
            }
        } catch (e: Exception) {
            "› web fetch error ($url): ${e.message}"
        }
    }

    private suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        try {
            // DuckDuckGo HTML (no API key)
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$q"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                val snippets = extractDdgResults(html).take(5)
                if (snippets.isEmpty()) {
                    val plain = htmlToText(html).take(4000)
                    "› search \"$query\"\n```\n$plain\n```"
                } else {
                    buildString {
                        appendLine("› search \"$query\"")
                        appendLine("```")
                        snippets.forEachIndexed { i, s ->
                            appendLine("${i + 1}. ${s.title}")
                            if (s.url.isNotBlank()) appendLine("   ${s.url}")
                            if (s.snippet.isNotBlank()) appendLine("   ${s.snippet}")
                            appendLine()
                        }
                        appendLine("```")
                    }
                }
            }
        } catch (e: Exception) {
            "› search error: ${e.message}"
        }
    }

    private data class Hit(val title: String, val url: String, val snippet: String)

    private fun extractDdgResults(html: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        // result blocks
        val block = Pattern.compile(
            """class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?class="result__snippet"[^>]*>(.*?)</(?:a|td)""",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )
        val m = block.matcher(html)
        while (m.find() && hits.size < 8) {
            val url = decodeHref(m.group(1) ?: "")
            val title = htmlToText(m.group(2) ?: "").trim()
            val snip = htmlToText(m.group(3) ?: "").trim()
            if (title.isNotBlank()) hits.add(Hit(title, url, snip))
        }
        if (hits.isEmpty()) {
            val simple = Pattern.compile(
                """class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                Pattern.DOTALL or Pattern.CASE_INSENSITIVE
            )
            val sm = simple.matcher(html)
            while (sm.find() && hits.size < 8) {
                hits.add(
                    Hit(
                        htmlToText(sm.group(2) ?: "").trim(),
                        decodeHref(sm.group(1) ?: ""),
                        ""
                    )
                )
            }
        }
        return hits
    }

    private fun decodeHref(href: String): String {
        // DDG wraps as //duckduckgo.com/l/?uddg=...
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)
        if (uddg != null) {
            return try {
                java.net.URLDecoder.decode(uddg.groupValues[1], "UTF-8")
            } catch (_: Exception) {
                href
            }
        }
        return if (href.startsWith("//")) "https:$href" else href
    }

    fun htmlToText(html: String): String {
        var s = html
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<noscript[^>]*>.*?</noscript>").replace(s, " ")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</p>").replace(s, "\n")
        s = Regex("(?i)</div>").replace(s, "\n")
        s = Regex("(?i)</li>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        s = Regex("[ \\t]+").replace(s, " ")
        s = Regex("\n{3,}").replace(s, "\n\n")
        return s.trim()
    }
}
