package com.example.aichat.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.ApiChatMessage
import com.example.aichat.data.ApiClient
import com.example.aichat.data.StreamClient
import com.example.aichat.data.WebAccess
import com.example.aichat.data.ChatMessage
import com.example.aichat.data.ChatRepository
import com.example.aichat.data.ChatRequest
import com.example.aichat.data.ChatSession
import com.example.aichat.data.Skill
import com.example.aichat.data.SkillInstaller
import com.example.aichat.data.Connector
import com.example.aichat.data.ConnectorPrompt
import com.example.aichat.data.Artifact
import com.example.aichat.data.ArtifactExtractor
import com.example.aichat.data.ReplyNotifier
import com.example.aichat.data.ContentPart
import com.example.aichat.data.ImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class PendingAttachment(
    val path: String,
    val name: String,
    val mime: String,
    val isImage: Boolean,
    val extractedText: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ChatRepository(application)
    private val appContext = application.applicationContext

    val chats: StateFlow<List<ChatSession>> = repo.chats
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val skills: StateFlow<List<Skill>> = repo.skills
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectors: StateFlow<List<Connector>> = repo.connectors
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val artifacts: StateFlow<List<Artifact>> = repo.artifacts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val setupDone: StateFlow<Boolean?> = repo.setupDone
        .map { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val agentMode: StateFlow<String> = repo.agentMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "agent")
    val themeMode: StateFlow<String> = repo.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val uiDensity: StateFlow<String> = repo.uiDensity
        .stateIn(viewModelScope, SharingStarted.Eagerly, "normal")
    val model: StateFlow<String> = repo.model
        .stateIn(viewModelScope, SharingStarted.Eagerly, "claude-opus-5-5")

    val baseUrl: StateFlow<String> = repo.baseUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "https://api.claudech.com/v1")

    val apiKey: StateFlow<String> = repo.apiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val systemPrompt: StateFlow<String> = repo.systemPrompt
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _currentChat = mutableStateOf<ChatSession?>(null)
    val currentChat: ChatSession? get() = _currentChat.value

    val messages = mutableStateListOf<ChatMessage>()

    private var streamJob: Job? = null


    val isLoading = mutableStateOf(false)
    /** last reply latency ms */
    val lastLatencyMs = mutableStateOf(0L)
    val lastInputTokens = mutableStateOf(0)
    val lastOutputTokens = mutableStateOf(0)
    val sessionCostUsd = mutableStateOf(0.0)
    val sessionStartedAt = System.currentTimeMillis()
    val inputHistory = mutableStateListOf<String>()
    private var historyIndex = -1

    val error = mutableStateOf<String?>(null)
    val drawerOpen = mutableStateOf(false)

    // Вложения перед отправкой (несколько)
    val pendingAttachments = mutableStateListOf<PendingAttachment>()

    init {
        viewModelScope.launch {
            combine(repo.chats, repo.currentChatId) { chatList, currentId ->
                chatList to currentId
            }.collect { (chatList, currentId) ->
                if (chatList.isEmpty()) {
                    createNewChat(select = true)
                } else {
                    val chat = chatList.find { it.id == currentId } ?: chatList.first()
                    if (_currentChat.value?.id != chat.id) {
                        selectChat(chat)
                    }
                }
            }
        }
    }

    fun selectChat(chat: ChatSession) {
        if (_currentChat.value?.id == chat.id) {
            drawerOpen.value = false
            return
        }
        _currentChat.value = chat
        // replace list in one step to reduce UI flicker
        messages.clear()
        messages.addAll(chat.messages)
        clearPending()
        drawerOpen.value = false
        viewModelScope.launch {
            repo.setCurrentChatId(chat.id)
        }
    }

    fun createNewChat(select: Boolean = true) {
        viewModelScope.launch {
            val newChat = ChatSession(title = "Новый чат")
            val updated = chats.value.toMutableList().apply { add(0, newChat) }
            // set UI first to avoid drawer/list flicker
            if (select) {
                _currentChat.value = newChat
                messages.clear()
                clearPending()
            }
            repo.saveChats(updated)
            if (select) repo.setCurrentChatId(newChat.id)
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            val updated = chats.value.filter { it.id != chatId }
            repo.saveChats(updated)
            if (_currentChat.value?.id == chatId) {
                if (updated.isNotEmpty()) selectChat(updated.first())
                else createNewChat()
            }
        }
    }

    fun renameChat(chatId: String, newTitle: String) {
        viewModelScope.launch {
            val updated = chats.value.map {
                if (it.id == chatId) it.copy(title = newTitle.trim().ifBlank { "Без названия" })
                else it
            }
            repo.saveChats(updated)
            if (_currentChat.value?.id == chatId) {
                _currentChat.value = updated.find { it.id == chatId }
            }
        }
    }

    private suspend fun persistCurrentChat() {
        val chat = _currentChat.value ?: return
        chat.messages.clear()
        chat.messages.addAll(messages)
        chat.updatedAt = System.currentTimeMillis()

        if (chat.title == "Новый чат" && messages.any { it.role == "user" }) {
            val firstUser = messages.first { it.role == "user" }
            chat.title = when {
                firstUser.content.isNotBlank() -> firstUser.content.take(40).let {
                    if (firstUser.content.length > 40) "$it…" else it
                }
                firstUser.attachmentName != null -> firstUser.attachmentName!!
                else -> "Вложение"
            }
        }

        val updated = chats.value.map { if (it.id == chat.id) chat else it }
        repo.saveChats(updated)
    }

    fun markSetupDone() {
        viewModelScope.launch { repo.setSetupDone(true) }
    }

    fun setAgentMode(mode: String) {
        viewModelScope.launch { repo.saveAgentMode(mode) }
    }
    fun setThemeMode(mode: String) {
        viewModelScope.launch { repo.saveThemeMode(mode) }
    }
    fun setUiDensity(d: String) {
        viewModelScope.launch { repo.saveUiDensity(d) }
    }
    fun updateReplyStats(streamStart: Long, userText: String, assistantText: String) {
        lastLatencyMs.value = (System.currentTimeMillis() - streamStart).coerceAtLeast(0)
        val inTok = (userText.length / 4).coerceAtLeast(1)
        val outTok = (assistantText.length / 4).coerceAtLeast(0)
        lastInputTokens.value = inTok
        lastOutputTokens.value = outTok
        // rough $ estimate: $3/M in + $15/M out
        sessionCostUsd.value += inTok * 3e-6 + outTok * 15e-6
    }
    fun pushInputHistory(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (inputHistory.isEmpty() || inputHistory.last() != t) {
            inputHistory.add(t)
            if (inputHistory.size > 50) inputHistory.removeAt(0)
        }
        historyIndex = -1
    }
    fun historyPrev(): String? {
        if (inputHistory.isEmpty()) return null
        if (historyIndex < 0) historyIndex = inputHistory.lastIndex
        else historyIndex = (historyIndex - 1).coerceAtLeast(0)
        return inputHistory.getOrNull(historyIndex)
    }
    fun historyNext(): String? {
        if (inputHistory.isEmpty() || historyIndex < 0) return null
        historyIndex++
        if (historyIndex > inputHistory.lastIndex) {
            historyIndex = -1
            return ""
        }
        return inputHistory.getOrNull(historyIndex)
    }
    fun saveSettings(baseUrl: String, apiKey: String, model: String, systemPrompt: String?) {
        viewModelScope.launch {
            repo.saveBaseUrl(baseUrl)
            repo.saveApiKey(apiKey)
            repo.saveModel(model)
            if (systemPrompt != null) repo.saveSystemPrompt(systemPrompt)
        }
    }

    fun clearPending() {
        pendingAttachments.clear()
    }

    fun removePending(path: String) {
        pendingAttachments.removeAll { it.path == path }
    }

    /** Прикрепляет любой файл */
    fun attachFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val resolver = appContext.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val name = queryFileName(uri) ?: "file"

                val isImage = mime.startsWith("image/")

                val dir = File(appContext.filesDir, "attachments")
                if (!dir.exists()) dir.mkdirs()

                val ext = name.substringAfterLast('.', if (isImage) "jpg" else "bin")
                val dest = File(dir, "${UUID.randomUUID()}.$ext")

                withContext(Dispatchers.IO) {
                    if (isImage) {
                        // Сжимаем изображение
                        resolver.openInputStream(uri)?.use { input ->
                            val original = BitmapFactory.decodeStream(input)
                                ?: throw Exception("Не удалось прочитать изображение")
                            val maxSide = 1280
                            val scaled = if (original.width > maxSide || original.height > maxSide) {
                                val ratio = minOf(
                                    maxSide.toFloat() / original.width,
                                    maxSide.toFloat() / original.height
                                )
                                Bitmap.createScaledBitmap(
                                    original,
                                    (original.width * ratio).toInt(),
                                    (original.height * ratio).toInt(),
                                    true
                                )
                            } else original

                            FileOutputStream(dest).use { out ->
                                val format = when {
                                    mime.contains("png") -> Bitmap.CompressFormat.PNG
                                    mime.contains("webp") -> Bitmap.CompressFormat.WEBP
                                    else -> Bitmap.CompressFormat.JPEG
                                }
                                scaled.compress(format, 85, out)
                            }
                            if (scaled != original) scaled.recycle()
                            original.recycle()
                        }
                    } else {
                        // Просто копируем файл
                        resolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(dest).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                // Извлекаем текст, если возможно
                val extracted = if (!isImage) {
                    withContext(Dispatchers.IO) { extractText(dest, mime, name) }
                } else null

                pendingAttachments.add(
                    PendingAttachment(
                        path = dest.absolutePath,
                        name = name,
                        mime = mime,
                        isImage = isImage,
                        extractedText = extracted
                    )
                )

            } catch (e: Exception) {
                error.value = "Не удалось прикрепить файл: ${e.message}"
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    /**
     * Пытается извлечь текст из файла.
     * Поддерживает: txt, md, json, xml, csv, html, код, pdf (простой), и т.д.
     */
    private fun extractText(file: File, mime: String, name: String): String? {
        val lower = name.lowercase()
        val textMimes = listOf(
            "text/", "application/json", "application/xml", "application/javascript",
            "application/x-javascript", "application/typescript"
        )
        val textExts = listOf(
            "txt", "md", "markdown", "json", "xml", "csv", "tsv", "html", "htm",
            "js", "ts", "jsx", "tsx", "kt", "java", "py", "rb", "go", "rs", "c", "cpp",
            "h", "hpp", "cs", "swift", "sql", "yaml", "yml", "toml", "ini", "cfg",
            "log", "sh", "bat", "ps1", "css", "scss", "less", "r", "php", "lua"
        )

        val isText = textMimes.any { mime.startsWith(it) } ||
                textExts.any { lower.endsWith(".$it") }

        if (isText) {
            return try {
                val text = file.readText(Charsets.UTF_8)
                // Ограничиваем размер, чтобы не раздувать контекст
                if (text.length > 100_000) {
                    text.take(100_000) + "\n\n… [файл обрезан, показаны первые 100 000 символов]"
                } else text
            } catch (e: Exception) {
                null
            }
        }

        // PDF — пробуем прочитать как текст (примитивно, без библиотеки)
        if (mime == "application/pdf" || lower.endsWith(".pdf")) {
            return try {
                // Очень простой fallback: многие PDF содержат текст в открытом виде
                val raw = file.readBytes()
                val asString = String(raw, Charsets.ISO_8859_1)
                // Ищем текстовые блоки между скобками (грубый эвристический парсер)
                val matches = Regex("""\((?:\\.|[^\\)])*\)""").findAll(asString)
                val texts = matches.mapNotNull { m ->
                    val s = m.value.removeSurrounding("(", ")")
                        .replace("\\n", "\n")
                        .replace("\\r", "")
                        .replace("\\t", "\t")
                        .replace(Regex("""\\(\d{3})""")) { "" } // octal
                    if (s.any { it.isLetter() }) s else null
                }.joinToString(" ")
                if (texts.length > 50) {
                    if (texts.length > 80_000) texts.take(80_000) + "\n\n… [текст PDF обрезан]"
                    else texts
                } else {
                    "[PDF-файл: $name — текстовое содержимое не удалось извлечь автоматически. Опишите, что нужно сделать с файлом.]"
                }
            } catch (e: Exception) {
                "[PDF-файл: $name — не удалось прочитать.]"
            }
        }

        if (lower.endsWith(".zip") || mime.contains("zip") || lower.endsWith(".jar")) {
            return try {
                val names = mutableListOf<String>()
                ZipInputStream(BufferedInputStream(file.inputStream())).use { zis ->
                    var e = zis.nextEntry
                    var n = 0
                    while (e != null && n < 100) {
                        if (!e.isDirectory) names.add(e.name)
                        zis.closeEntry()
                        e = zis.nextEntry
                        n++
                    }
                }
                buildString {
                    appendLine("ZIP «$name» (${formatSize(file.length())}), файлов: ${names.size}")
                    names.take(80).forEach { appendLine("  - $it") }
                    if (names.size > 80) appendLine("  …")
                }
            } catch (e: Exception) {
                "[архив $name — ${e.message}]"
            }
        }
        if (lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") ||
            lower.endsWith(".gz") || lower.endsWith(".bz2") || lower.endsWith(".xz")) {
            return "[архив $name (${formatSize(file.length())})]"
        }

        return "[Файл: $name (${formatSize(file.length())}), mime=$mime]"
    }


    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "%.1f КБ".format(bytes / 1024.0)
            else -> "%.1f МБ".format(bytes / (1024.0 * 1024.0))
        }
    }

    private fun imageToBase64DataUrl(path: String, mime: String): String? {
        return try {
            val bytes = File(path).readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mime;base64,$b64"
        } catch (e: Exception) {
            null
        }
    }

    fun sendMessage(text: String) {
        pushInputHistory(text)
        val attachments = pendingAttachments.toList()
        if ((text.isBlank() && attachments.isEmpty()) || isLoading.value) return
        clearPending()

        // Формируем текст сообщения + все текстовые вложения
        var finalText = text.trim()
        val imageAtt = attachments.filter { it.isImage }
        val otherAtt = attachments.filter { !it.isImage }
        for (a in otherAtt) {
            val block = if (a.extractedText != null) {
                "--- Содержимое файла «${a.name}» ---\n${a.extractedText}\n--- Конец файла ---"
            } else {
                "[Прикреплён файл: ${a.name}]"
            }
            finalText = if (finalText.isBlank()) block else "$finalText\n\n$block"
        }
        if (imageAtt.isNotEmpty() && finalText.isBlank()) {
            finalText = "[Изображения: " + imageAtt.joinToString { it.name } + "]"
        }

        // Первое изображение как attachmentPath для vision (остальные — в текст/доп. сообщения)
        val primaryImg = imageAtt.firstOrNull()
        val userMsg = ChatMessage(
            role = "user",
            content = finalText,
            attachmentPath = primaryImg?.path ?: attachments.firstOrNull()?.path,
            attachmentName = primaryImg?.name ?: attachments.firstOrNull()?.name,
            attachmentMime = primaryImg?.mime ?: attachments.firstOrNull()?.mime,
            isImage = primaryImg != null
        )
        messages.add(userMsg)
        // Доп. изображения отдельными сообщениями-вложениями для vision
        for (img in imageAtt.drop(1)) {
            messages.add(
                ChatMessage(
                    role = "user",
                    content = "[image: ${img.name}]",
                    attachmentPath = img.path,
                    attachmentName = img.name,
                    attachmentMime = img.mime,
                    isImage = true
                )
            )
        }
        isLoading.value = true
        error.value = null

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val streamStart = System.currentTimeMillis()
            try {
                persistCurrentChat()

                val baseUrl = repo.baseUrl.first()
                val apiKey = repo.apiKey.first()
                val model = repo.model.first()
                val systemPrompt = repo.systemPrompt.first()
                val skillsText = SkillInstaller.buildSkillsPrompt(skills.value)
                val connectorsText = ConnectorPrompt.build(connectors.value)
                val svgHint = """
## Drawing / SVG
When the user asks to draw / sketch / illustrate, output an SVG in a fenced block labeled svg with xmlns and viewBox. The app renders it as an image. Prefer SVG over ASCII art.
""".trimIndent()
                
                val mode = repo.agentMode.first()
                val modeHint = when (mode) {
                    "ask" -> "MODE=ask: answer clearly and concisely. Do not propose multi-step tool plans unless asked."
                    "plan" -> "MODE=plan: produce a structured plan first. Do not claim you executed changes; outline steps only."
                    else -> "MODE=agent: be proactive, use tools/skills when helpful, prefer concrete actions."
                }
                
                val docsHint = """
## Documents / tables / presentations
You cannot run Python on the device. To create downloadable files, output fenced code blocks:
- header file:name.csv or language csv for tables
- header file:name.md or language markdown for documents  
- header file:name.html for pages or simple slide decks (use h2 sections)
- Markdown pipe tables are also saved as CSV artifacts under /files
""".trimIndent()
                val fullSystem = listOf(systemPrompt, modeHint, skillsText, connectorsText, WebAccess.SYSTEM_HINT, svgHint, docsHint)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")

                if (apiKey.isBlank()) {
                    throw Exception("API-ключ не задан. Откройте настройки.")
                }

                val history = mutableListOf<ApiChatMessage>()

                if (fullSystem.isNotBlank()) {
                    history.add(
                        ApiChatMessage(
                            role = "system",
                            content = listOf(ContentPart(type = "text", text = fullSystem))
                        )
                    )
                }

                for (msg in messages.filter { it.role != "system" }) {
                    val parts = mutableListOf<ContentPart>()

                    if (msg.content.isNotBlank()) {
                        parts.add(ContentPart(type = "text", text = msg.content))
                    }

                    if (msg.isImage && msg.attachmentPath != null && msg.role == "user") {
                        val dataUrl = imageToBase64DataUrl(
                            msg.attachmentPath,
                            msg.attachmentMime ?: "image/jpeg"
                        )
                        if (dataUrl != null) {
                            parts.add(
                                ContentPart(
                                    type = "image_url",
                                    imageUrl = ImageUrl(url = dataUrl)
                                )
                            )
                        }
                    }

                    if (parts.isEmpty()) {
                        parts.add(ContentPart(type = "text", text = ""))
                    }

                    history.add(ApiChatMessage(role = msg.role, content = parts))
                }

                // Пустое сообщение ассистента — наполняем по токенам
                messages.add(ChatMessage("assistant", ""))
                val assistantIndex = messages.lastIndex

                val reply = withContext(Dispatchers.IO) {
                    val buffer = StringBuilder()
                    var lastPost = 0L
                    fun flushUi(force: Boolean = false) {
                        if (buffer.isEmpty()) return
                        val now = System.currentTimeMillis()
                        if (!force && now - lastPost < 48 && buffer.length < 32) return
                        val chunk = buffer.toString()
                        buffer.setLength(0)
                        lastPost = now
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (assistantIndex in messages.indices) {
                                val cur = messages[assistantIndex]
                                messages[assistantIndex] = cur.copy(content = cur.content + chunk)
                            }
                        }
                    }
                    val full = StreamClient.streamChat(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        messages = history
                    ) { delta ->
                        buffer.append(delta)
                        flushUi()
                    }
                    flushUi(force = true)
                    kotlinx.coroutines.delay(60)
                    full
                }

                if (reply.isBlank() && messages.getOrNull(assistantIndex)?.content.isNullOrBlank()) {
                    throw Exception("Пустой ответ от сервера")
                }

                var finalReply = messages.getOrNull(assistantIndex)?.content ?: reply
                val httpResult = runHttpConnectorsIfNeeded(finalReply)
                if (httpResult != null) {
                    finalReply = finalReply + "\n\n" + httpResult
                    if (assistantIndex in messages.indices) {
                        messages[assistantIndex] = messages[assistantIndex].copy(content = finalReply)
                    }
                }

                // Internet plugin: fetch / search
                val webResult = WebAccess.processMarkers(finalReply)
                if (webResult != null) {
                    finalReply = finalReply + "\n\n" + webResult
                    if (assistantIndex in messages.indices) {
                        messages[assistantIndex] = messages[assistantIndex].copy(content = finalReply)
                    }
                    // Одна доработка ответа с учётом данных из сети
                    try {
                        val followup = history.toMutableList()
                        followup.add(
                            ApiChatMessage(
                                role = "assistant",
                                content = listOf(ContentPart(type = "text", text = finalReply))
                            )
                        )
                        followup.add(
                            ApiChatMessage(
                                role = "user",
                                content = listOf(
                                    ContentPart(
                                        type = "text",
                                        text = "Tool results from the internet are above. Using only those facts, give a clear final answer to the user. Do not output more [[fetch:]] or [[search:]] markers unless critically needed."
                                    )
                                )
                            )
                        )
                        messages.add(ChatMessage("assistant", ""))
                        val idx2 = messages.lastIndex
                        val refined = withContext(Dispatchers.IO) {
                            StreamClient.streamChat(baseUrl, apiKey, model, followup) { delta ->
                                viewModelScope.launch(Dispatchers.Main.immediate) {
                                    if (idx2 in messages.indices) {
                                        val cur = messages[idx2]
                                        messages[idx2] = cur.copy(content = cur.content + delta)
                                    }
                                }
                            }
                        }
                        if (refined.isNotBlank()) {
                            finalReply = messages.getOrNull(idx2)?.content ?: refined
                        }
                    } catch (_: Exception) {
                        // leave tool output as-is
                    }
                }

                val found = ArtifactExtractor.extract(finalReply)
                if (found.isNotEmpty()) {
                    val merged = (found + artifacts.value).distinctBy { it.content.hashCode() }.take(50)
                    repo.saveArtifacts(merged)
                }
                updateReplyStats(streamStart, finalText, finalReply)
                persistCurrentChat()
                ReplyNotifier.notifyReply(appContext, finalReply)
            } catch (e: Exception) {
                val msg = ApiClient.friendlyError(e)
                messages.add(ChatMessage("assistant", "Ошибка: $msg"))
                error.value = msg
                persistCurrentChat()
            } finally {
                isLoading.value = false
                streamJob = null
            }
        }
    }

    fun stopGeneration() {
        StreamClient.cancel()
        streamJob?.cancel()
        streamJob = null
        isLoading.value = false
        // if last assistant is empty, remove it
        if (messages.lastOrNull()?.role == "assistant" && messages.lastOrNull()?.content.isNullOrBlank()) {
            messages.removeAt(messages.lastIndex)
        } else if (messages.lastOrNull()?.role == "assistant") {
            val last = messages.last()
            messages[messages.lastIndex] = last.copy(content = last.content + "\n\n› stopped")
        }
    }

    fun clearCurrentChat() {
        messages.clear()
        clearPending()
        viewModelScope.launch { persistCurrentChat() }
    }

    fun installSkillFromUrl(url: String, onResult: (Result<Skill>) -> Unit) {
        viewModelScope.launch {
            val result = SkillInstaller.installFromUrl(url)
            result.onSuccess { skill ->
                val updated = skills.value.toMutableList()
                // не дублируем по имени
                updated.removeAll { it.name == skill.name }
                updated.add(0, skill)
                repo.saveSkills(updated)
            }
            onResult(result)
        }
    }

    fun toggleSkill(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val updated = skills.value.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            repo.saveSkills(updated)
        }
    }

    fun deleteSkill(id: String) {
        viewModelScope.launch {
            repo.saveSkills(skills.value.filter { it.id != id })
        }
    }

    fun installSkillDirect(skill: Skill) {
        viewModelScope.launch {
            val updated = skills.value.toMutableList()
            updated.removeAll { it.name == skill.name }
            updated.add(0, skill)
            repo.saveSkills(updated)
        }
    }

    fun addConnector(connector: Connector) {
        viewModelScope.launch {
            val updated = connectors.value.toMutableList()
            updated.removeAll { it.name.equals(connector.name, ignoreCase = true) }
            updated.add(0, connector)
            repo.saveConnectors(updated)
        }
    }

    fun toggleConnector(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repo.saveConnectors(connectors.value.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            })
        }
    }

    fun deleteConnector(id: String) {
        viewModelScope.launch {
            repo.saveConnectors(connectors.value.filter { it.id != id })
        }
    }

    fun deleteArtifact(id: String) {
        viewModelScope.launch {
            repo.saveArtifacts(artifacts.value.filter { it.id != id })
        }
    }

    fun renameArtifact(id: String, name: String) {
        viewModelScope.launch {
            repo.saveArtifacts(artifacts.value.map {
                if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it
            })
        }
    }

    fun exportCurrentChat() {
        viewModelScope.launch {
            val chat = currentChat ?: return@launch
            val md = buildString {
                appendLine("# ${chat.title}")
                appendLine()
                for (m in chat.messages) {
                    appendLine("**${m.role}:**")
                    appendLine(m.content)
                    appendLine()
                }
            }
            try {
                val name = "chat_${chat.title.take(20).replace(Regex("[^a-zA-Z0-9_-]"), "_")}.md"
                val file = java.io.File(getApplication<Application>().getExternalFilesDir(null), name)
                file.writeText(md)
                // also as artifact
                val art = Artifact(
                    name = name,
                    content = md,
                    language = "markdown"
                )
                repo.saveArtifacts((listOf(art) + artifacts.value).distinctBy { it.name }.take(50))
                messages.add(ChatMessage("assistant", "› exported to /files as $name"))
                persistCurrentChat()
            } catch (e: Exception) {
                messages.add(ChatMessage("assistant", "Ошибка экспорта: ${e.message}"))
            }
        }
    }

    /** Простой HTTP GET/POST для включённых коннекторов по маркеру в ответе модели */
    suspend fun runHttpConnectorsIfNeeded(assistantText: String): String? {
        // Формат: [[http:METHOD:connectorName:/path]] или [[http:GET:Name:https://full-url]]
        val regex = Regex("""\[\[http:(GET|POST):([^:\]]+):([^\]]+)\]\]""", RegexOption.IGNORE_CASE)
        val match = regex.find(assistantText) ?: return null
        val method = match.groupValues[1].uppercase()
        val name = match.groupValues[2].trim()
        val pathOrUrl = match.groupValues[3].trim()
        val conn = connectors.value.firstOrNull {
            it.enabled && it.name.equals(name, ignoreCase = true)
        }
        val url = when {
            pathOrUrl.startsWith("http") -> pathOrUrl
            conn != null && conn.baseUrl.isNotBlank() ->
                conn.baseUrl.trimEnd('/') + "/" + pathOrUrl.trimStart('/')
            else -> pathOrUrl
        }
        return try {
            withContext(Dispatchers.IO) {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val builder = okhttp3.Request.Builder().url(url)
                if (conn?.authHeader?.isNotBlank() == true) {
                    builder.header("Authorization", conn.authHeader)
                }
                if (method == "POST") {
                    builder.post(okhttp3.RequestBody.create(null, ByteArray(0)))
                } else {
                    builder.get()
                }
                client.newCall(builder.build()).execute().use { resp ->
                    val body = resp.body?.string()?.take(8000) ?: ""
                    "› HTTP ${resp.code} $method $url\n```\n$body\n```"
                }
            }
        } catch (e: Exception) {
            "Ошибка HTTP: ${e.message}"
        }
    }
}
