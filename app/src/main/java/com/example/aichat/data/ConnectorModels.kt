package com.example.aichat.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Коннектор ≈ упрощённый аналог плагина/MCP из Claude Code.
 * Описывает внешний сервис; описание попадает в system prompt,
 * чтобы модель знала, чем может пользоваться.
 */
@Serializable
data class Connector(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    /** Базовый URL API (опционально) */
    val baseUrl: String = "",
    /** Заголовок Authorization, если нужен (например Bearer sk-...) */
    val authHeader: String = "",
    /** Подсказка модели: как вызывать / что умеет */
    val instructions: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

object ConnectorPrompt {
    fun build(connectors: List<Connector>): String {
        val active = connectors.filter { it.enabled }
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("## Подключённые коннекторы (plugins)")
            appendLine("У пользователя включены HTTP-коннекторы. Чтобы выполнить запрос, в ответе одной строкой напиши маркер:")
            appendLine("[[http:GET:ИмяКоннектора:/path]] или [[http:GET:Имя:https://full-url]]")
            appendLine("[[http:POST:ИмяКоннектора:/path]]")
            appendLine("Приложение выполнит запрос и добавит результат.")
            appendLine()
            for (c in active) {
                appendLine("### ${c.name}")
                appendLine(c.description)
                if (c.baseUrl.isNotBlank()) appendLine("API: ${c.baseUrl}")
                if (c.instructions.isNotBlank()) {
                    appendLine("Инструкции:")
                    appendLine(c.instructions)
                }
                appendLine()
            }
        }.trim()
    }
}
