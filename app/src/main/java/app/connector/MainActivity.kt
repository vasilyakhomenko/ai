package app.connector

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent { GlassChatApp() }
    }
}

data class Attachment(val name: String, val text: String)
private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
private val bg = Color(0xFF08090B)
private val panel = Color(0xCC1A1C20)
private val line = Color(0xFF34373D)
private val white = Color(0xFFF4F5F6)
private val muted = Color(0xFF969BA4)

@Composable
fun GlassChatApp() {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val dao = remember { db.chatDao() }
    val settingsStore = remember { SettingsStore(context) }
    val vault = remember { ApiKeyVault(context) }
    val chats by dao.observeChats().collectAsStateWithLifecycle(emptyList<ChatEntity>())
    val pref by settingsStore.flow.collectAsStateWithLifecycle(initialValue = androidx.datastore.preferences.core.emptyPreferences())
    val storedSettings = remember(pref) { settingsStore.read(pref) }
    var baseUrl by remember(storedSettings) { mutableStateOf(storedSettings.baseUrl) }
    var model by remember(storedSettings) { mutableStateOf(storedSettings.model) }
    var systemPrompt by remember(storedSettings) { mutableStateOf(storedSettings.systemPrompt) }
    var temperature by remember(storedSettings) { mutableFloatStateOf(storedSettings.temperature) }
    var apiKey by remember { mutableStateOf(vault.read()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val messages by remember(selectedId) { selectedId?.let { dao.observeMessages(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList<MessageEntity>()) }.collectAsStateWithLifecycle(emptyList<MessageEntity>())
    var draft by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var webSearch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var chatsOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchChats by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ChatEntity?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var editValue by remember { mutableStateOf("") }
    var exportDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val callRef = remember { AtomicReference<Call?>(null) }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(chats) {
        if (selectedId == null && chats.isNotEmpty()) selectedId = chats.first().id
        else if (selectedId == null && chats.isEmpty()) {
            val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
            dao.insertChat(ChatEntity(id, "New chat", now, now)); selectedId = id
        }
    }
    LaunchedEffect(selectedId, messages.size, messages.lastOrNull()?.content) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    fun newChat() {
        scope.launch {
            val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
            dao.insertChat(ChatEntity(id, "New chat", now, now)); selectedId = id; draft = ""; attachment = null; error = null; chatsOpen = false
        }
    }
    fun updateTitleIfDefault(id: String, text: String) {
        scope.launch { val c = dao.getChat(id) ?: return@launch; if (c.title == "New chat") dao.updateChat(c.copy(title = text.take(42).ifBlank { "New chat" }, updatedAt = System.currentTimeMillis())) }
    }
    fun generate(userText: String, existingUserMessageId: Long? = null) {
        val chatId = selectedId ?: return
        if (busy || (userText.isBlank() && attachment == null)) return
        val file = attachment; attachment = null; draft = ""; error = null; busy = true; streamedText.set("")
        scope.launch {
            var currentAssistantId: Long? = null
            try {
                val fullText = buildString { append(userText); if (file != null) append("\n\n[Attached file: ${file.name}]\n${file.text}") }
                if (existingUserMessageId == null) dao.insertMessage(MessageEntity(chatId = chatId, role = "user", content = fullText))
                else dao.updateMessage(existingUserMessageId, fullText)
                updateTitleIfDefault(chatId, userText.ifBlank { file?.name ?: "New chat" })
                val searchContext = if (webSearch && userText.isNotBlank()) withContext(Dispatchers.IO) { searchWeb(userText) } else ""
                val history = dao.observeMessages(chatId).first().toMutableList()
                // On edit, discard later turns so the conversation branches from the edited prompt.
                if (existingUserMessageId != null) {
                    val editedIndex = history.indexOfFirst { it.id == existingUserMessageId }
                    if (editedIndex >= 0) {
                        val laterMessages = history.drop(editedIndex + 1)
                        laterMessages.forEach { dao.deleteMessage(it.id) }
                        history.subList(editedIndex + 1, history.size).clear()
                    }
                }
                val assistantId = dao.insertMessage(MessageEntity(chatId = chatId, role = "assistant", content = ""))
                currentAssistantId = assistantId
                withContext(Dispatchers.IO) {
                    streamChat(baseUrl, apiKey, model, systemPrompt, temperature, history, searchContext, callRef) { chunk ->
                        val previous = streamedText.get()
                        val next = previous + chunk
                        streamedText.set(next)
                        scope.launch { dao.updateMessage(assistantId, next) }
                    }
                }
                if (streamedText.get().isBlank()) dao.updateMessage(assistantId, "Пустой ответ модели. Проверьте настройки API и совместимость Streaming API.")
                dao.getChat(chatId)?.let { dao.updateChat(it.copy(updatedAt = System.currentTimeMillis())) }
            } catch (e: CancellationException) {
                currentAssistantId?.let { if (streamedText.get().isBlank()) dao.updateMessage(it, "Generation stopped.") }
            } catch (e: Exception) {
                if (callRef.get()?.isCanceled() == true) currentAssistantId?.let { if (streamedText.get().isBlank()) dao.updateMessage(it, "Generation stopped.") }
                else error = e.message ?: "Не удалось выполнить запрос"
            }
            finally { callRef.set(null); busy = false; streamedText.set("") }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { attachment = try { readAttachment(context, uri) } catch (e: Exception) { error = e.message; null } }
    }
    fun exportContent(markdown: Boolean, uri: Uri?) {
        if (uri != null) scope.launch { try { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
            val title = chats.find { it.id == selectedId }?.title ?: "GlassChat conversation"
            if (markdown) out.write("# $title\n\n") else out.write("$title\n${"=".repeat(title.length)}\n\n")
            messages.forEach { m ->
                val who = if (m.role == "user") "You" else model
                if (markdown) out.write("## $who\n\n${m.content}\n\n") else out.write("$who:\n${m.content}\n\n")
            }
        } } catch (e: Exception) { error = "Export failed: ${e.message}" } }
    }
    val exportMarkdownPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { exportContent(true, it) }
    val exportTextPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { exportContent(false, it) }

    MaterialTheme(colorScheme = darkColorScheme(primary = white, background = bg, surface = panel, onSurface = white, onPrimary = Color.Black)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF15171C), bg, Color(0xFF050607))))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.Default.Menu, "Chats") { chatsOpen = true }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("GlassChat", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = white, letterSpacing = (-0.6).sp)
                        Text(model, fontSize = 10.sp, color = muted, maxLines = 1)
                    }
                    GlassIconButton(Icons.Default.FileDownload, "Export") { exportDialog = true }
                    GlassIconButton(Icons.Default.Add, "New chat") { newChat() }
                    GlassIconButton(Icons.Default.Tune, "Settings") { settingsOpen = true }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(panel).border(1.dp, line.copy(alpha = .65f), RoundedCornerShape(18.dp)).padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (apiKey.isBlank()) Color(0xFF777D86) else Color(0xFFB8F2CF), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(model, fontSize = 12.sp, color = white, fontWeight = FontWeight.Medium); Text(baseUrl, fontSize = 10.sp, color = muted, maxLines = 1) }
                    Text(if (apiKey.isBlank()) "NO KEY" else "CONNECTED", color = muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
                }
                Spacer(Modifier.height(10.dp))
                if (messages.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(.96f, 1.04f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "logo")
                        Box(Modifier.size((76 * pulse).dp).clip(RoundedCornerShape(27.dp)).background(Brush.linearGradient(listOf(Color.White, Color(0xFF9EA4AD)))), contentAlignment = Alignment.Center) { Icon(Icons.Default.AllInclusive, null, tint = Color.Black, modifier = Modifier.size(40.dp)) }
                        Spacer(Modifier.height(22.dp)); Text("A quieter space to think.", color = white, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Text("Your models. Your conversations. Your workflow.", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp, bottom = 24.dp))
                        listOf("Explain a complex idea", "Review a piece of code", "Research something new").forEach { hint ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(panel).border(1.dp, line.copy(alpha = .55f), RoundedCornerShape(16.dp)).clickable { draft = hint }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Text(hint, color = white, fontSize = 13.sp, modifier = Modifier.weight(1f)); Icon(Icons.Default.ArrowUpward, null, tint = muted, modifier = Modifier.size(15.dp)) }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { index, msg ->
                            MessageBubble(msg, model, onCopy = { clipboard.setText(AnnotatedString(msg.content)) }, onEdit = { editTarget = msg; editValue = msg.content }, onRetry = {
                                if (!busy && msg.role == "assistant") {
                                    scope.launch { dao.deleteMessage(msg.id); val previous = messages.take(index).lastOrNull { it.role == "user" }; if (previous != null) generate(previous.content, previous.id) }
                                }
                            })
                        }
                        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = white); Spacer(Modifier.width(9.dp)); Text("Generating response…", color = muted, fontSize = 11.sp) } }
                    }
                }
                AnimatedVisibility(attachment != null) { attachment?.let { a -> Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(13.dp)).background(panel).padding(9.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AttachFile, null, tint = white, modifier = Modifier.size(16.dp)); Text(a.name, color = white, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1); IconButton(onClick = { attachment = null }, modifier = Modifier.size(25.dp)) { Icon(Icons.Default.Close, null, tint = muted, modifier = Modifier.size(15.dp)) } } } }
                AnimatedVisibility(error != null) { error?.let { Text(it, color = Color(0xFFFF9C9C), fontSize = 11.sp, modifier = Modifier.padding(bottom = 7.dp)) } }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xDD202329)).border(1.dp, Color(0xFF444952), RoundedCornerShape(24.dp)).padding(7.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        BasicTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 43.dp, max = 135.dp).padding(horizontal = 10.dp, vertical = 11.dp), textStyle = TextStyle(color = white, fontSize = 14.sp, lineHeight = 20.sp), cursorBrush = SolidColor(white), decorationBox = { inner -> Box { if (draft.isEmpty()) Text("Message GlassChat…", color = Color(0xFF7D838D), fontSize = 14.sp); inner() } })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.AttachFile, "Attach file", tint = muted, modifier = Modifier.size(18.dp)) }
                            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(if (webSearch) white else Color(0xFF353A42)).clickable { webSearch = !webSearch }.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Language, null, tint = if (webSearch) Color.Black else muted, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp)); Text("Web", color = if (webSearch) Color.Black else muted, fontSize = 10.sp) }
                        }
                    }
                    IconButton(enabled = busy || draft.isNotBlank() || attachment != null, onClick = { if (busy) callRef.get()?.cancel() else generate(draft.trim()) }) {
                        Box(Modifier.size(43.dp).clip(RoundedCornerShape(15.dp)).background(if (busy || draft.isNotBlank() || attachment != null) white else Color(0xFF41464F)), contentAlignment = Alignment.Center) { Icon(if (busy) Icons.Default.Stop else Icons.Default.ArrowUpward, null, tint = Color.Black) }
                    }
                }
                Text("Your provider processes prompts and attached content", color = Color(0xFF666C75), fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 7.dp))
            }

            if (settingsOpen) SettingsSheet(baseUrl, { baseUrl = it }, apiKey, { apiKey = it }, model, { model = it }, systemPrompt, { systemPrompt = it }, temperature, { temperature = it }, onSave = {
                scope.launch { settingsStore.save(baseUrl.trim().trimEnd('/'), model.trim(), systemPrompt, temperature); vault.save(apiKey.trim()); settingsOpen = false }
            }, onClose = { settingsOpen = false })
            if (chatsOpen) ChatDrawer(chats, selectedId, searchChats, { searchChats = it }, onSelect = { selectedId = it; chatsOpen = false }, onNew = { newChat() }, onRename = { renameTarget = it; renameValue = it.title }, onDelete = { c -> scope.launch { dao.deleteChat(c); if (selectedId == c.id) selectedId = null } }, onClose = { chatsOpen = false })
            if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false }, title = { Text("Export conversation") }, text = { Text("Choose the file format.") }, confirmButton = { TextButton(onClick = { exportDialog = false; exportMarkdownPicker.launch("glasschat-${System.currentTimeMillis()}.md") }) { Text("Markdown") } }, dismissButton = { TextButton(onClick = { exportDialog = false; exportTextPicker.launch("glasschat-${System.currentTimeMillis()}.txt") }) { Text("Plain text") } }, containerColor = Color(0xFF202329), titleContentColor = white, textContentColor = white)
            if (renameTarget != null) {
                AlertDialog(
                    onDismissRequest = { renameTarget = null },
                    title = { Text("Rename chat") },
                    text = { OutlinedTextField(renameValue, { renameValue = it }, singleLine = true) },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                val target = renameTarget
                                if (target != null) dao.updateChat(target.copy(title = renameValue.ifBlank { target.title }, updatedAt = System.currentTimeMillis()))
                                renameTarget = null
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
                    containerColor = Color(0xFF202329), titleContentColor = white, textContentColor = white
                )
            }
            if (editTarget != null) {
                AlertDialog(
                    onDismissRequest = { editTarget = null },
                    title = { Text("Edit message") },
                    text = { OutlinedTextField(editValue, { editValue = it }, minLines = 4, maxLines = 9) },
                    confirmButton = { TextButton(onClick = { val target = editTarget!!; editTarget = null; generate(editValue, target.id) }) { Text("Save & resend") } },
                    dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } },
                    containerColor = Color(0xFF202329), titleContentColor = white, textContentColor = white
                )
            }
        }
    }
}

private val streamedText = java.util.concurrent.atomic.AtomicReference("")

@Composable
fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(39.dp).clip(RoundedCornerShape(14.dp)).background(panel).border(1.dp, line.copy(alpha = .6f), RoundedCornerShape(14.dp))) { Icon(icon, description, tint = white, modifier = Modifier.size(19.dp)) }
}

@Composable
fun MessageBubble(msg: MessageEntity, model: String, onCopy: () -> Unit, onEdit: () -> Unit, onRetry: () -> Unit) {
    val user = msg.role == "user"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (user) Color(0x332F343D) else Color(0x661A1D22)).border(1.dp, line.copy(alpha = .38f), RoundedCornerShape(18.dp)).padding(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (user) "YOU" else model.uppercase(), color = muted, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onCopy, modifier = Modifier.size(27.dp)) { Icon(Icons.Default.ContentCopy, "Copy", tint = muted, modifier = Modifier.size(14.dp)) }
            if (user) IconButton(onClick = onEdit, modifier = Modifier.size(27.dp)) { Icon(Icons.Default.Edit, "Edit", tint = muted, modifier = Modifier.size(14.dp)) }
            else IconButton(onClick = onRetry, modifier = Modifier.size(27.dp)) { Icon(Icons.Default.Refresh, "Retry", tint = muted, modifier = Modifier.size(15.dp)) }
        }
        Spacer(Modifier.height(5.dp))
        val svg = if (!user) extractSvg(msg.content) else null
        if (svg != null) { MarkdownMessage(cleanSvgText(msg.content)); Spacer(Modifier.height(8.dp)); SvgPreview(svg) }
        else MarkdownMessage(msg.content)
    }
}

@Composable
fun MarkdownMessage(content: String) {
    val clipboard = LocalClipboardManager.current
    val lines = content.split('\n')
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var codeBuffer = ""
        var codeMode = false
        val segments = mutableListOf<Pair<Boolean, String>>()
        val textBuffer = StringBuilder()
        lines.forEach { lineText ->
            if (lineText.trimStart().startsWith("```")) {
                if (!codeMode) { if (textBuffer.isNotEmpty()) { segments.add(false to textBuffer.toString()); textBuffer.clear() }; codeMode = true }
                else { segments.add(true to codeBuffer); codeBuffer = ""; codeMode = false }
            } else if (codeMode) codeBuffer += lineText + "\n" else textBuffer.append(lineText).append('\n')
        }
        if (codeMode) segments.add(true to codeBuffer) else if (textBuffer.isNotEmpty()) segments.add(false to textBuffer.toString())
        segments.forEach { (isCode, text) ->
            if (isCode) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF090B0E)).border(1.dp, line, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text(text.trimEnd(), color = Color(0xFFD5DCE6), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                IconButton(onClick = { clipboard.setText(AnnotatedString(text.trimEnd())) }, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.ContentCopy, "Copy code", tint = muted, modifier = Modifier.size(14.dp)) }
            } else {
                text.lines().filter { it.isNotBlank() }.forEach { raw ->
                    val clean = raw.trimStart()
                    when {
                        clean.startsWith("### ") -> Text(clean.removePrefix("### "), color = white, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        clean.startsWith("## ") -> Text(clean.removePrefix("## "), color = white, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        clean.startsWith("# ") -> Text(clean.removePrefix("# "), color = white, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        clean.startsWith("- ") || clean.startsWith("* ") -> Text("•  ${clean.drop(2)}", color = Color(0xFFE2E4E8), fontSize = 13.sp, lineHeight = 20.sp)
                        clean.matches(Regex("\\d+\\. .*")) -> Text(clean, color = Color(0xFFE2E4E8), fontSize = 13.sp, lineHeight = 20.sp)
                        else -> Text(buildInlineMarkdown(clean), color = Color(0xFFE2E4E8), fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

fun buildInlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    val regex = Regex("(\\*\\*.*?\\*\\*|`[^`]+`|\\*[^*]+\\*)")
    var cursor = 0
    regex.findAll(source).forEach { m ->
        append(source.substring(cursor, m.range.first))
        val token = m.value
        when {
            token.startsWith("**") -> pushStyle(SpanStyle(fontWeight = FontWeight.Bold)).also { append(token.removeSurrounding("**")); pop() }
            token.startsWith("`") -> pushStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = Color(0xFF30343B))).also { append(token.trim('`')); pop() }
            else -> pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)).also { append(token.trim('*')); pop() }
        }
        cursor = m.range.last + 1
    }
    append(source.substring(cursor))
}

@Composable
fun SettingsSheet(base: String, setBase: (String) -> Unit, key: String, setKey: (String) -> Unit, model: String, setModel: (String) -> Unit, system: String, setSystem: (String) -> Unit, temp: Float, setTemp: (Float) -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xBB000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(15.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF1A1D22)).border(1.dp, Color(0xFF515761), RoundedCornerShape(26.dp)).padding(19.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Model settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = white, modifier = Modifier.weight(1f)); IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = white) } }
            SettingsField("BASE URL", base, setBase, "https://api.openai.com/v1")
            SettingsField("API KEY · ENCRYPTED ON DEVICE", key, setKey, "API key", secret = true)
            SettingsField("MODEL ID", model, setModel, "gpt-4o-mini")
            SettingsField("SYSTEM PROMPT", system, setSystem, "Instructions for the assistant", multiline = true)
            Text("Temperature · ${"%.1f".format(temp)}", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)); Slider(temp, setTemp, valueRange = 0f..2f)
            Text("The API key is encrypted with AES-GCM using a key held by Android Keystore. Web search is performed by the app and added as context.", color = muted, fontSize = 10.sp, lineHeight = 15.sp)
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = Color.Black), shape = RoundedCornerShape(14.dp)) { Text("Save settings") }
        }
    }
}

@Composable
fun SettingsField(label: String, value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false, multiline: Boolean = false) {
    Text(label, color = muted, fontSize = 9.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 13.dp, bottom = 6.dp))
    BasicTextField(value, onChange, singleLine = !multiline, visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0D0F12)).padding(13.dp).heightIn(min = 22.dp, max = if (multiline) 120.dp else 42.dp), textStyle = TextStyle(color = white, fontSize = 13.sp), decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Color(0xFF6F7580), fontSize = 13.sp); inner() } })
}

@Composable
fun ChatDrawer(chats: List<ChatEntity>, selected: String?, query: String, setQuery: (String) -> Unit, onSelect: (String) -> Unit, onNew: () -> Unit, onRename: (ChatEntity) -> Unit, onDelete: (ChatEntity) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xBB000000))) {
        Column(Modifier.fillMaxHeight().fillMaxWidth(.88f).align(Alignment.CenterStart).background(Color(0xFF15181D)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Conversations", fontSize = 20.sp, color = white, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = white) } }
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp), colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = Color.Black)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(7.dp)); Text("New chat") }
            OutlinedTextField(query, setQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search chats") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            LazyColumn(Modifier.weight(1f).padding(top = 10.dp)) {
                items(chats.filter { it.title.contains(query, true) }, key = { it.id }) { chat ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(13.dp)).background(if (chat.id == selected) Color(0xFF303640) else Color.Transparent).clickable { onSelect(chat.id) }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(chat.title, color = white, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        IconButton(onClick = { onRename(chat) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Edit, null, tint = muted, modifier = Modifier.size(14.dp)) }
                        IconButton(onClick = { onDelete(chat) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.DeleteOutline, null, tint = muted, modifier = Modifier.size(15.dp)) }
                    }
                }
            }
        }
    }
}

fun streamChat(base: String, key: String, model: String, system: String, temp: Float, history: List<MessageEntity>, webContext: String, callRef: AtomicReference<Call?>, onChunk: (String) -> Unit) {
    require(base.startsWith("https://") || base.startsWith("http://")) { "Base URL must start with http(s)" }
    require(key.isNotBlank()) { "Укажите API Key в настройках" }
    val effectiveSystem = if (webContext.isBlank()) system else "$system\n\nWeb search results retrieved by the app. Use as sourced context and cite URLs inline:\n$webContext"
    val arr = JSONArray().put(JSONObject().put("role", "system").put("content", effectiveSystem))
    history.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
    val body = JSONObject().put("model", model).put("messages", arr).put("temperature", temp).put("stream", true)
    val request = Request.Builder().url(base.trimEnd('/') + "/chat/completions").post(body.toString().toRequestBody("application/json".toMediaType())).header("Authorization", "Bearer $key").header("Accept", "text/event-stream").build()
    val call = http.newCall(request); callRef.set(call)
    call.execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${response.body?.string()?.take(700)}")
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        while (true) {
            if (call.isCanceled()) break
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim(); if (payload == "[DONE]") break
            try {
                val choice = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)
                val delta = choice?.optJSONObject("delta")
                val content = delta?.opt("content")
                val chunk = when (content) { is String -> content; is JSONArray -> (0 until content.length()).mapNotNull { i -> content.optJSONObject(i)?.optString("text") }.joinToString(""); else -> "" }
                if (chunk.isNotEmpty()) onChunk(chunk)
            } catch (_: Exception) { }
        }
    }
}

fun searchWeb(query: String): String {
    val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
    val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) GlassChat/2.1").build()
    return http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Web search HTTP ${response.code}")
        val doc = Jsoup.parse(response.body?.string().orEmpty())
        val results = doc.select(".result").take(7)
        if (results.isEmpty()) "No web results found for: $query"
        else results.mapIndexed { i, el ->
            val title = el.selectFirst(".result__title")?.text().orEmpty()
            val link = el.selectFirst(".result__title a")?.absUrl("href").orEmpty().ifBlank { el.selectFirst(".result__url")?.text().orEmpty() }
            val snippet = el.selectFirst(".result__snippet")?.text().orEmpty()
            "${i + 1}. $title\n$snippet\nURL: $link"
        }.joinToString("\n\n")
    }
}

fun readAttachment(context: Context, uri: Uri): Attachment {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else "attachment" } ?: "attachment"
    val mime = context.contentResolver.getType(uri).orEmpty(); val ext = name.substringAfterLast('.', "").lowercase()
    val text = if (mime == "application/pdf" || ext == "pdf") {
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
        input.use { PDDocument.load(it).use { pdf -> PDFTextStripper().getText(pdf) } }
    } else {
        require(mime.startsWith("text/") || mime in listOf("application/json", "application/xml", "application/javascript") || ext in listOf("txt", "md", "json", "csv", "xml", "html", "kt", "java", "py", "js", "ts", "css", "svg", "yaml", "yml", "log", "gradle", "kts")) { "Поддерживаются текстовые документы, исходники и PDF." }
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
    }
    require(text.length <= 180_000) { "Файл слишком большой (лимит 180 КБ текста)" }
    return Attachment(name, text)
}

fun extractSvg(content: String): String? {
    val fenced = Regex("```(?:svg|xml|html)?\\s*([\\s\\S]*?<svg[\\s\\S]*?</svg>)\\s*```", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)
    val raw = fenced ?: Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE).find(content)?.value
    return raw?.takeIf { it.length < 500_000 && it.contains("<svg", true) }
}
fun cleanSvgText(content: String): String = content.replace(Regex("```(?:svg|xml|html)?[\\s\\S]*?```"), "[SVG preview]").take(1000)

@Composable
fun SvgPreview(svg: String) {
    AndroidView(factory = { ctx -> WebView(ctx).apply {
        settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.domStorageEnabled = false
        webViewClient = WebViewClient(); setBackgroundColor(android.graphics.Color.WHITE)
        val safeSvg = svg.replace(Regex("(?i)on[a-z]+\\s*=\\s*(['\"]).*?\\1"), "").replace(Regex("(?i)(href|xlink:href)\\s*=\\s*(['\"])https?://.*?\\2"), "")
        loadDataWithBaseURL(null, "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0;padding:12px;display:flex;justify-content:center;align-items:center;'>$safeSvg</body></html>", "text/html", "UTF-8", null)
    } }, modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(14.dp)).background(Color.White))
}
