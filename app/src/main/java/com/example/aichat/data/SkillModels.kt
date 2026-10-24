package com.example.aichat.data

import kotlinx.serialization.Serializable

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val body: String,           // markdown instructions after frontmatter
    val sourceUrl: String = "",
    val enabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis()
)
