package com.example.aichat.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChatMessage(
    val role: String,                    // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Путь к локальному файлу вложения */
    val attachmentPath: String? = null,
    /** Имя файла для отображения */
    val attachmentName: String? = null,
    /** MIME-тип */
    val attachmentMime: String? = null,
    /** true = изображение (отправляется как vision) */
    val isImage: Boolean = false
)

@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Новый чат",
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)
