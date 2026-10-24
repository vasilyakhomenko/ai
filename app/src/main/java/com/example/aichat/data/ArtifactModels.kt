package com.example.aichat.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Artifact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val content: String,
    val language: String = "text",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Извлекает артефакты из ответа:
 * - ```lang ... ```
 * - ```file:name.ext ... ```
 * - ```artifact:name.ext ... ```
 * - markdown-таблицы → .csv
 */
object ArtifactExtractor {
    private val namedBlock = Regex(
        """```(?:file:|artifact:)?([^\n`]+)\n(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val mdTable = Regex(
        """(?:^|\n)(\|[^\n]+\|\n\|[-:| ]+\|\n(?:\|[^\n]+\|\n?)+)""",
        setOf(RegexOption.MULTILINE)
    )

    fun extract(assistantText: String): List<Artifact> {
        val out = mutableListOf<Artifact>()
        var idx = 0

        for (m in namedBlock.findAll(assistantText)) {
            val header = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
            if (body.isBlank()) continue
            idx++

            val isNamed = header.contains('.') || header.startsWith("file:", true)
            val name: String
            val lang: String
            if (isNamed || header.contains('/')) {
                val raw = header.removePrefix("file:").removePrefix("artifact:").trim()
                name = raw.substringAfterLast('/').ifBlank { "artifact_$idx.txt" }
                lang = name.substringAfterLast('.', "text")
            } else {
                lang = header.ifBlank { "text" }
                val ext = extFor(lang)
                name = "artifact_$idx.$ext"
            }

            // skip pure svg inline (handled by renderer) unless named file
            if (lang.equals("svg", true) && !isNamed) continue

            out.add(Artifact(name = name, content = body, language = lang))
        }

        // Markdown tables → CSV
        for (m in mdTable.findAll(assistantText)) {
            val table = m.groupValues[1].trim()
            val csv = mdTableToCsv(table)
            if (csv.lines().size >= 2) {
                idx++
                out.add(
                    Artifact(
                        name = "table_$idx.csv",
                        content = csv,
                        language = "csv"
                    )
                )
            }
        }

        return out.distinctBy { it.name + it.content.take(80) }.take(30)
    }

    private fun extFor(lang: String): String = when (lang.lowercase()) {
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "json" -> "json"
        "html", "htm" -> "html"
        "css" -> "css"
        "sql" -> "sql"
        "bash", "sh", "shell" -> "sh"
        "markdown", "md" -> "md"
        "csv", "tsv" -> "csv"
        "xml" -> "xml"
        "yaml", "yml" -> "yml"
        "pptx", "presentation" -> "html" // HTML slides stand-in
        "docx", "document" -> "md"
        "xlsx", "table" -> "csv"
        else -> "txt"
    }

    private fun mdTableToCsv(table: String): String {
        val lines = table.lines().filter { it.contains('|') }
        if (lines.size < 2) return ""
        val rows = lines.filterIndexed { i, line ->
            // skip separator |---|---|
            i == 0 || !line.replace(" ", "").matches(Regex("\\|?[-:|]+\\|?"))
        }
        return rows.joinToString("\n") { line ->
            line.trim().trim('|').split("|").joinToString(",") { cell ->
                val c = cell.trim()
                if (c.contains(',') || c.contains('"')) "\"${c.replace("\"", "\"\"")}\"" else c
            }
        }
    }
}
