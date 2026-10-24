package com.example.aichat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

object SkillInstaller {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Принимает URL вида:
     * - https://github.com/owner/repo/blob/main/skills/foo/SKILL.md
     * - https://raw.githubusercontent.com/owner/repo/main/skills/foo/SKILL.md
     * - https://github.com/owner/repo (попробует найти SKILL.md в корне или skills/)
     */
    suspend fun installFromUrl(url: String): Result<Skill> = withContext(Dispatchers.IO) {
        try {
            val rawUrl = toRawUrl(url.trim())
            val request = Request.Builder().url(rawUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Не удалось скачать (${response.code}). Проверьте ссылку.")
                    )
                }
                val text = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Пустой ответ"))
                parseSkillMdPublic(text, rawUrl)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun toRawUrl(url: String): String {
        // github.com/owner/repo/blob/branch/path -> raw.githubusercontent.com/owner/repo/branch/path
        val blobRegex = Regex(
            """https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)"""
        )
        blobRegex.matchEntire(url)?.let { m ->
            val (owner, repo, branch, path) = m.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        }
        if (url.contains("raw.githubusercontent.com")) return url
        // Просто репозиторий — пробуем стандартные пути
        val repoRegex = Regex("""https?://github\.com/([^/]+)/([^/]+)/?$""")
        repoRegex.matchEntire(url.removeSuffix(".git"))?.let { m ->
            val (owner, repo) = m.destructured
            // Пользователь должен указать путь к SKILL.md; для репо целиком не угадываем
            return "https://raw.githubusercontent.com/$owner/$repo/main/SKILL.md"
        }
        return url
    }

    fun parseSkillMdPublic(text: String, sourceUrl: String): Result<Skill> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("---")) {
            return Result.failure(Exception("Это не SKILL.md (нет YAML frontmatter)"))
        }
        val end = trimmed.indexOf("---", 3)
        if (end < 0) {
            return Result.failure(Exception("Некорректный frontmatter"))
        }
        val front = trimmed.substring(3, end).trim()
        val body = trimmed.substring(end + 3).trim()

        var name = ""
        var description = ""
        for (line in front.lines()) {
            val t = line.trim()
            when {
                t.startsWith("name:") ->
                    name = t.removePrefix("name:").trim().trim('"', '\'')
                t.startsWith("description:") ->
                    description = t.removePrefix("description:").trim().trim('"', '\'')
            }
        }
        if (name.isBlank()) {
            name = "skill-${UUID.randomUUID().toString().take(8)}"
        }
        if (description.isBlank()) {
            description = "Навык без описания"
        }

        return Result.success(
            Skill(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                body = body.ifBlank { description },
                sourceUrl = sourceUrl,
                enabled = true
            )
        )
    }

    /** Собирает текст активных навыков для system prompt */
    fun buildSkillsPrompt(skills: List<Skill>): String {
        val active = skills.filter { it.enabled }
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("У тебя подключены следующие навыки (skills). Используй их, когда запрос пользователя им соответствует.")
            appendLine()
            for (s in active) {
                appendLine("### Навык: ${s.name}")
                appendLine(s.description)
                appendLine()
                appendLine(s.body)
                appendLine()
                appendLine("---")
                appendLine()
            }
        }.trim()
    }
}
