package com.example.aichat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aichat_prefs")

class ChatRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Settings
    private val BASE_URL = stringPreferencesKey("base_url")
    private val API_KEY = stringPreferencesKey("api_key")
    private val MODEL = stringPreferencesKey("model")
    private val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")

    // Chats
    private val CHATS = stringPreferencesKey("chats")
    private val CURRENT_CHAT_ID = stringPreferencesKey("current_chat_id")
    private val SKILLS = stringPreferencesKey("skills")
    private val CONNECTORS = stringPreferencesKey("connectors")
    private val ARTIFACTS = stringPreferencesKey("artifacts")
    private val AGENT_MODE = stringPreferencesKey("agent_mode")
    private val UI_DENSITY = stringPreferencesKey("ui_density")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val SETUP_DONE = booleanPreferencesKey("setup_done")

    // ——— Settings ———
    val baseUrl: Flow<String> = context.dataStore.data.map {
        it[BASE_URL] ?: "https://api.claudech.com/v1"
    }
    val apiKey: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val model: Flow<String> = context.dataStore.data.map { it[MODEL] ?: "claude-opus-5-5" }
    val systemPrompt: Flow<String> = context.dataStore.data.map {
        it[SYSTEM_PROMPT] ?: "Ты полезный русскоязычный ассистент. Отвечай на русском языке, если пользователь пишет по-русски."
    }
    val setupDone: Flow<Boolean> = context.dataStore.data.map { it[SETUP_DONE] ?: false }

    suspend fun setSetupDone(done: Boolean = true) {
        context.dataStore.edit { it[SETUP_DONE] = done }
    }

    suspend fun saveBaseUrl(url: String) {
        context.dataStore.edit { it[BASE_URL] = url.trim().removeSuffix("/") }
    }

    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { it[API_KEY] = key.trim() }
    }

    suspend fun saveModel(model: String) {
        context.dataStore.edit { it[MODEL] = model.trim() }
    }

    suspend fun saveSystemPrompt(prompt: String) {
        context.dataStore.edit { it[SYSTEM_PROMPT] = prompt.trim() }
    }

    // ——— Chats ———
    val chats: Flow<List<ChatSession>> = context.dataStore.data.map { prefs ->
        val raw = prefs[CHATS] ?: "[]"
        try {
            json.decodeFromString<List<ChatSession>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val currentChatId: Flow<String?> = context.dataStore.data.map { it[CURRENT_CHAT_ID] }

    suspend fun saveChats(chats: List<ChatSession>) {
        context.dataStore.edit {
            it[CHATS] = json.encodeToString(chats)
        }
    }

    suspend fun setCurrentChatId(id: String?) {
        context.dataStore.edit {
            if (id == null) it.remove(CURRENT_CHAT_ID)
            else it[CURRENT_CHAT_ID] = id
        }
    }

    // ——— Skills ———
    val skills: Flow<List<Skill>> = context.dataStore.data.map { prefs ->
        val raw = prefs[SKILLS] ?: "[]"
        try {
            json.decodeFromString<List<Skill>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveSkills(skills: List<Skill>) {
        context.dataStore.edit {
            it[SKILLS] = json.encodeToString(skills)
        }
    }

    val connectors: Flow<List<Connector>> = context.dataStore.data.map { prefs ->
        val raw = prefs[CONNECTORS] ?: "[]"
        try {
            json.decodeFromString<List<Connector>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveConnectors(list: List<Connector>) {
        context.dataStore.edit {
            it[CONNECTORS] = json.encodeToString(list)
        }
    }

    val artifacts: Flow<List<Artifact>> = context.dataStore.data.map { prefs ->
        val raw = prefs[ARTIFACTS] ?: "[]"
        try {
            json.decodeFromString<List<Artifact>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveArtifacts(list: List<Artifact>) {
        context.dataStore.edit {
            it[ARTIFACTS] = json.encodeToString(list)
        }
    }

    val agentMode: Flow<String> = context.dataStore.data.map { it[AGENT_MODE] ?: "agent" }
    suspend fun saveAgentMode(mode: String) {
        context.dataStore.edit { it[AGENT_MODE] = mode }
    }
    val uiDensity: Flow<String> = context.dataStore.data.map { it[UI_DENSITY] ?: "normal" }
    suspend fun saveUiDensity(d: String) {
        context.dataStore.edit { it[UI_DENSITY] = d }
    }
    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "system" }
    suspend fun saveThemeMode(m: String) {
        context.dataStore.edit { it[THEME_MODE] = m }
    }
}
