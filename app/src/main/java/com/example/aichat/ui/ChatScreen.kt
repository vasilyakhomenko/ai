package com.example.aichat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.aichat.data.ChatMessage
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit = {},
    onOpenConnectors: () -> Unit = {},
    onOpenFiles: () -> Unit = {}
) {
    val chats by viewModel.chats.collectAsState()
    val currentChat = viewModel.currentChat
    val messages = viewModel.messages
    val isLoading by viewModel.isLoading
    val pendingAttachments = viewModel.pendingAttachments
    val modelName by viewModel.model.collectAsState()
    val agentMode by viewModel.agentMode.collectAsState()
    val lastLatencyMs by viewModel.lastLatencyMs
    val lastInTok by viewModel.lastInputTokens
    val lastOutTok by viewModel.lastOutputTokens
    val sessionCost by viewModel.sessionCostUsd
    val sessionStart = remember { viewModel.sessionStartedAt }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var previewSvgText by remember { mutableStateOf<String?>(null) }
    var showCommands by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { viewModel.attachFile(it) }
    }

    // Смена чата — один скролл без анимации
    val chatId = currentChat?.id
    LaunchedEffect(chatId) {
        kotlinx.coroutines.delay(16)
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }
    // Стрим: подскролл только при новых сообщениях (не на каждый токен)
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= messages.size - 3) {
            listState.scrollToItem(messages.size - 1)
        }
    }
    var showScrollDown by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 0 && last < total - 2
        }.collect { showScrollDown = it }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                drawerTonalElevation = 0.dp
            ) {
                ChatListDrawerContent(
                    chats = chats,
                    currentChatId = currentChat?.id,
                    onSelect = { chat ->
                        scope.launch { drawerState.close() }
                        viewModel.selectChat(chat)
                    },
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        viewModel.createNewChat()
                    },
                    onDelete = { viewModel.deleteChat(it) },
                    onRename = { id, title -> viewModel.renameChat(id, title) },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    onOpenSkills = {
                        scope.launch { drawerState.close() }
                        onOpenSkills()
                    },
                    onOpenConnectors = {
                        scope.launch { drawerState.close() }
                        onOpenConnectors()
                    },
                    onOpenFiles = {
                        scope.launch { drawerState.close() }
                        onOpenFiles()
                    }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {},
            floatingActionButton = {
                if (showScrollDown && messages.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch { listState.scrollToItem(messages.size - 1) }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Вниз")
                    }
                }
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {

                    // Вложение над строкой ввода
                    AnimatedVisibility(
                        visible = pendingAttachments.isNotEmpty(),
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut()
                    ) {
                        if (pendingAttachments.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                pendingAttachments.forEach { att ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(0.7f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp, MaterialTheme.colorScheme.outline.copy(0.4f)
                                        )
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (att.isImage) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data(File(att.path)).crossfade(true).build(),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .clickable { if (att.isImage) previewImagePath = att.path },
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.Description, null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                att.name,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 120.dp)
                                            )
                                            IconButton(
                                                onClick = { viewModel.removePending(att.path) },
                                                modifier = Modifier.size(22.dp)
                                            ) {
                                                Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }


                    // Slash-command autocomplete
                    val slashSuggestions = remember(input.text) {
                        val cmds = listOf(
                            "/config" to "настройки API",
                            "/files" to "артефакты",
                            "/skills" to "навыки и коннекторы",
                            "/clear" to "очистить текущий чат",
                            "/export" to "экспорт чата в файл"
                        )
                        if (!input.text.startsWith("/")) emptyList()
                        else {
                            val q = input.text.removePrefix("/").lowercase()
                            cmds.filter {
                                it.first.removePrefix("/").startsWith(q) ||
                                    it.first.contains(q, true) ||
                                    it.second.contains(q, true)
                            }.sortedBy { !it.first.removePrefix("/").startsWith(q) }
                        }
                    }
                    if (slashSuggestions.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.85f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.outline.copy(0.4f)
                            )
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                slashSuggestions.forEach { (cmd, desc) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                when (cmd) {
                                                    "/config" -> onOpenSettings()
                                                    "/files" -> onOpenFiles()
                                                    "/skills" -> onOpenSkills()
                                                    "/clear" -> viewModel.clearCurrentChat()
                                                    "/export" -> viewModel.exportCurrentChat()
                                                                                                    }
                                                input = TextFieldValue("")
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            cmd,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            desc,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onBackground.copy(0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    
                    // Attach + stats one line
                    val sessSec = ((System.currentTimeMillis() - sessionStart) / 1000).toInt()
                    val sessStr = "%d:%02d".format(sessSec / 60, sessSec % 60)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val attachInteraction = remember { MutableInteractionSource() }
                        Text(
                            "прикрепить файл",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                            modifier = Modifier
                                .clickable(
                                    enabled = !isLoading,
                                    indication = null,
                                    interactionSource = attachInteraction
                                ) { filePicker.launch("*/*") }
                                .padding(end = 12.dp)
                        )
                        Text(
                            buildString {
                                if (lastLatencyMs > 0) append("${lastLatencyMs}ms · ")
                                if (lastInTok + lastOutTok > 0) append("↑${lastInTok} ↓${lastOutTok} · ")
                                append(sessStr)
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(0.4f),
                            maxLines = 1
                        )
                    }

                    // Terminal prompt row: model› input ↑
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val shortModel = modelName.substringAfterLast('/').take(18)
                        Text(
                            "$shortModel›",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 28.dp, max = 96.dp)
                                .padding(vertical = 2.dp)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionUp -> {
                                            val prev = viewModel.historyPrev()
                                            if (prev != null) {
                                                input = TextFieldValue(prev, TextRange(prev.length))
                                                true
                                            } else false
                                        }
                                        Key.DirectionDown -> {
                                            val n = viewModel.historyNext()
                                            if (n != null) {
                                                input = TextFieldValue(n, TextRange(n.length))
                                                true
                                            } else false
                                        }
                                        Key.Tab -> {
                                            if (input.text.startsWith("/")) {
                                                val cmds = listOf("/config","/files","/skills","/clear","/export")
                                                val match = cmds.firstOrNull { it.startsWith(input.text, ignoreCase = true) }
                                                if (match != null) {
                                                    input = TextFieldValue(match, TextRange(match.length))
                                                    true
                                                } else false
                                            } else false
                                        }
                                        else -> false
                                    }
                                },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val cmd = input.text.trim().lowercase()
                                    when {
                                        cmd == "/config" || cmd == "/settings" -> {
                                            onOpenSettings(); input = TextFieldValue("")
                                        }
                                        cmd == "/files" || cmd == "/file" -> {
                                            onOpenFiles(); input = TextFieldValue("")
                                        }
                                        cmd == "/skills" || cmd == "/skill" || cmd == "/connectors" -> {
                                            onOpenSkills(); input = TextFieldValue("")
                                        }
                                        cmd == "/clear" -> {
                                            viewModel.clearCurrentChat(); input = TextFieldValue("")
                                        }
                                        cmd == "/export" -> {
                                            viewModel.exportCurrentChat(); input = TextFieldValue("")
                                        }
                                        (input.text.isNotBlank() || pendingAttachments.isNotEmpty()) && !isLoading -> {
                                            viewModel.sendMessage(input.text)
                                            input = TextFieldValue("")
                                            keyboardController?.hide()
                                        }
                                    }
                                }
                            ),
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (input.text.isEmpty()) {
                                        Text(
                                            "type a message or /command",
                                            color = MaterialTheme.colorScheme.onBackground.copy(0.3f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            maxLines = 1
                                        )
                                    } else if (input.text.startsWith("/")) {
                                        val allCmds = listOf(
                                            "/config", "/files", "/skills",
                                            "/clear", "/export"
                                        )
                                        val match = allCmds.firstOrNull {
                                            it.startsWith(input.text, ignoreCase = true) && it.length > input.text.length
                                        }
                                        if (match != null) {
                                            Text(
                                                match,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp,
                                                color = MaterialTheme.colorScheme.onBackground.copy(0.28f),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    inner()
                                }
                            }
                        )
                        val canSend = (input.text.isNotBlank() || pendingAttachments.isNotEmpty()) && !isLoading
                        Text(
                            if (isLoading) "■" else "↑",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isLoading || canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground.copy(0.25f),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable(enabled = isLoading || canSend) {
                                    if (isLoading) {
                                        viewModel.stopGeneration()
                                        return@clickable
                                    }
                                    val cmd = input.text.trim().lowercase()
                                    when {
                                        cmd == "/config" || cmd == "/settings" -> {
                                            onOpenSettings(); input = TextFieldValue("")
                                        }
                                        cmd == "/files" || cmd == "/file" -> {
                                            onOpenFiles(); input = TextFieldValue("")
                                        }
                                        cmd == "/skills" || cmd == "/skill" || cmd == "/connectors" -> {
                                            onOpenSkills(); input = TextFieldValue("")
                                        }
                                        cmd == "/clear" -> {
                                            viewModel.clearCurrentChat(); input = TextFieldValue("")
                                        }
                                        cmd == "/export" -> {
                                            viewModel.exportCurrentChat(); input = TextFieldValue("")
                                        }
                                        canSend -> {
                                            viewModel.sendMessage(input.text)
                                            input = TextFieldValue("")
                                            keyboardController?.hide()
                                        }
                                    }
                                }
                        )
                    }
                }
            },

        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Chat content scrolls UNDER the translucent top bar
                if (messages.isEmpty() && !isLoading) {
                    ClaudeCodeWelcome(
                        chats = chats,
                        onSelectChat = { viewModel.selectChat(it) },
                        modelName = modelName,
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(top = 48.dp)
                    )
                } else {
                    key(chatId) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(
                            top = 56.dp,
                            bottom = 12.dp
                        )
                    ) {
                        item { Spacer(Modifier.statusBarsPadding()) }
                        items(messages.size, key = { it }) { idx ->
                            val msg = messages[idx]
                            val streaming = isLoading && idx == messages.lastIndex && msg.role == "assistant"
                            ConsoleMessage(msg, isStreaming = streaming, onImageClick = { previewImagePath = it }, onSvgClick = { previewSvgText = it })
                        }
                        if (isLoading && messages.lastOrNull()?.role != "assistant") {
                            item { ThinkingLine() }
                        }
                    }
                    } // key chatId
                }

                // Console glass top: blur + less transparency
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .blur(20.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                            .statusBarsPadding()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Text(
                                "menu",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(0.7f),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .clickable { scope.launch { drawerState.open() } }
                            )
                            val status = if (isLoading) "thinking" else "done"
                            Text(
                                "$modelName @ $status",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (isLoading) MaterialTheme.colorScheme.primary
                                else Color(0xFF4EC9B0),
                                modifier = Modifier.align(Alignment.Center),
                                maxLines = 1
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.3f))
                    }
                }
            }
        }
    }

    // Fullscreen image preview
    if (previewImagePath != null) {
        Dialog(
            onDismissRequest = { previewImagePath = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(File(previewImagePath!!))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clickable { /* keep open */ },
                    contentScale = ContentScale.Fit
                )
                Text(
                    "×",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .clickable { previewImagePath = null }
                )
            }
        }
    }
    // Fullscreen SVG + download
    if (previewSvgText != null) {
        val ctx = LocalContext.current
        Dialog(
            onDismissRequest = { previewSvgText = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                SvgImage(
                    previewSvgText!!,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Text(
                        "×",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.End)
                            .clickable { previewSvgText = null }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "скачать",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = Color(0xFFD97706),
                        modifier = Modifier
                            .clickable {
                                try {
                                    val name = "connector_${System.currentTimeMillis()}.svg"
                                    val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                                        ?: ctx.filesDir
                                    val f = File(dir, name)
                                    f.writeText(previewSvgText!!)
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "Сохранено: ${f.absolutePath}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "Ошибка: ${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun BlinkingPrompt() {
    val infinite = rememberInfiniteTransition(label = "prompt")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "promptAlpha"
    )
    Text(
        "›",
        fontFamily = FontFamily.Monospace,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        modifier = Modifier.padding(end = 8.dp)
    )
}

@Composable
private fun DrawingIndicator() {
    val infinite = rememberInfiniteTransition(label = "draw")
    val a by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "da"
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Text("✦", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = a))
        Spacer(Modifier.width(8.dp))
        Text(
            "drawing",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(0.45f * a)
        )
    }
}

@Composable
private fun ThinkingLine() {
    val infinite = rememberInfiniteTransition(label = "think")
    val dots by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 3.99f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    val n = dots.toInt().coerceIn(0, 3)
    val blink by infinite.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Text(
            "✦",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = blink)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "thinking" + ".".repeat(n),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(0.45f * blink)
        )
    }
}

@Composable
fun ConsoleMessage(msg: ChatMessage, isStreaming: Boolean = false, onImageClick: (String) -> Unit = {}, onSvgClick: (String) -> Unit = {}) {
    val isUser = msg.role == "user"
    val isError = msg.content.startsWith("Ошибка:")
    // без анимации появления — иначе дёргается при каждом токене стрима
    Box {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(0.dp),
            color = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isUser) "you" else "claude",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isUser) Color(0xFF4EC9B0)
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))

                    if (msg.isImage && msg.attachmentPath != null) {
                        val file = File(msg.attachmentPath)
                        if (file.exists()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(file).crossfade(true).build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { msg.attachmentPath?.let { onImageClick(it) } },
                                contentScale = ContentScale.Crop
                            )
                            if (msg.content.isNotBlank()) Spacer(Modifier.height(6.dp))
                        }
                    }

                    if (msg.content.isNotBlank()) {
                        val looksSvg = msg.content.contains("<svg", ignoreCase = true) ||
                            msg.content.contains("```svg", ignoreCase = true)
                        val svgDone = msg.content.contains("</svg>", ignoreCase = true)
                        if (isStreaming && looksSvg && !svgDone) {
                            DrawingIndicator()
                        } else if (isStreaming && looksSvg && svgDone) {
                            SelectionContainer { MessageContentWithCode(msg.content, isError, onSvgClick = onSvgClick) }
                        } else if (isStreaming && !looksSvg) {
                            SelectionContainer { MessageContentWithCode(msg.content, isError, onSvgClick = onSvgClick) }
                        } else {
                            SelectionContainer { MessageContentWithCode(msg.content, isError, onSvgClick = onSvgClick) }
                        }
                    } else if (isStreaming) {
                        ThinkingLine()
                    }
                }
            }
        }
    }
}

// keep old name if referenced
@Composable
fun MessageBubble(msg: ChatMessage) = ConsoleMessage(msg)

@Composable
fun TerminalMessage(msg: ChatMessage) = ConsoleMessage(msg)


@Composable
fun MessageContentWithCode(content: String, isError: Boolean, onSvgClick: (String) -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val parts = remember(content) { splitCodeBlocks(content) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { idx, part ->
          key(idx to part.lang to part.text.take(32)) {
            if (part.isCode && part.lang.equals("svg", ignoreCase = true)) {
                SvgImage(
                    part.text,
                    modifier = Modifier.clickable { onSvgClick(part.text) }
                )
            } else if (part.isCode) {
                var expanded by remember(idx, part.text.take(24)) {
                    mutableStateOf(part.text.lines().size <= 12)
                }
                var copied by remember(idx) { mutableStateOf(false) }
                val copyInteraction = remember(idx) { MutableInteractionSource() }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outline.copy(0.3f)
                    )
                ) {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.85f))
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (expanded) "▾" else "▸",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                part.lang.ifBlank { "code" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            )
                            Text(
                                "  ·  ${part.text.lines().size} lines",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(0.35f)
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (copied) "✓ copied" else "copy",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (copied) Color(0xFF4EC9B0) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(
                                    indication = null,
                                    interactionSource = copyInteraction
                                ) {
                                    clipboard.setText(AnnotatedString(part.text))
                                    copied = true
                                }
                            )
                        }
                        if (expanded) {
                            SelectionContainer {
                                Text(
                                    syntaxHighlight(part.text, part.lang, MaterialTheme.colorScheme.onBackground),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .fillMaxWidth()
                                )
                            }
                        } else {
                            Text(
                                part.text.lines().take(2).joinToString("\n") + "\n…",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(0.4f),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            } else {
                SelectionContainer { SvgAwareText(part.text, isError, onSvgClick = onSvgClick) }
            }
          } // key
        }
    }
}

private data class ContentPartUi(val text: String, val isCode: Boolean, val lang: String = "")

private fun splitCodeBlocks(content: String): List<ContentPartUi> {
    val regex = Regex("```(\\w*)\\n([\\s\\S]*?)```")
    val result = mutableListOf<ContentPartUi>()
    var last = 0
    for (m in regex.findAll(content)) {
        if (m.range.first > last) {
            val plain = content.substring(last, m.range.first)
            if (plain.isNotBlank()) result.add(ContentPartUi(plain.trim(), false))
        }
        result.add(ContentPartUi(m.groupValues[2].trimEnd(), true, m.groupValues[1]))
        last = m.range.last + 1
    }
    if (last < content.length) {
        val plain = content.substring(last)
        if (plain.isNotBlank()) result.add(ContentPartUi(plain.trim(), false))
    }
    if (result.isEmpty()) result.add(ContentPartUi(content, false))
    return result
}



@Composable
fun SvgAwareText(text: String, isError: Boolean, onSvgClick: (String) -> Unit = {}) {
    val segments = remember(text) { extractSvgBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((seg, isSvg) in segments) {
            if (isSvg) {
                SvgImage(seg, modifier = Modifier.clickable { onSvgClick(seg) })
            } else if (seg.isNotBlank()) {
                ColoredMarkdownText(seg, isError)
            }
        }
    }
}

@Composable
fun ColoredMarkdownText(text: String, isError: Boolean) {
    val base = if (isError) Color(0xFFDC2626) else MaterialTheme.colorScheme.onBackground
    val boldC = MaterialTheme.colorScheme.primary
    val codeC = Color(0xFF4EC9B0)
    val linkC = Color(0xFF569CD6)
    val annotated = remember(text, isError) {
        buildAnnotatedString {
            // simple: **bold**, `code`, plain
            val regex = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|https?://\\S+)")
            var last = 0
            for (m in regex.findAll(text)) {
                if (m.range.first > last) {
                    withStyle(SpanStyle(color = base)) { append(text.substring(last, m.range.first)) }
                }
                val token = m.value
                when {
                    token.startsWith("**") && token.endsWith("**") ->
                        withStyle(SpanStyle(color = boldC, fontWeight = FontWeight.Bold)) {
                            append(token.removeSurrounding("**"))
                        }
                    token.startsWith("`") && token.endsWith("`") ->
                        withStyle(SpanStyle(color = codeC)) {
                            append(token.removeSurrounding("`"))
                        }
                    token.startsWith("http") ->
                        withStyle(SpanStyle(color = linkC)) { append(token) }
                    else -> withStyle(SpanStyle(color = base)) { append(token) }
                }
                last = m.range.last + 1
            }
            if (last < text.length) {
                withStyle(SpanStyle(color = base)) { append(text.substring(last)) }
            }
        }
    }
    Text(
        annotated,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontFamily = FontFamily.Monospace
    )
}

fun syntaxHighlight(code: String, lang: String, defaultColor: Color): AnnotatedString {
    val keywords = setOf(
        "fun", "val", "var", "if", "else", "when", "return", "class", "object", "import",
        "package", "for", "while", "true", "false", "null", "def", "from", "async", "await",
        "const", "let", "function", "public", "private", "void", "int", "string", "boolean"
    )
    return buildAnnotatedString {
        val lines = code.split("\n")
        lines.forEachIndexed { li, line ->
            if (li > 0) append("\n")
            // comment
            val commentIdx = line.indexOf("//").let { if (it < 0) line.indexOf("#") else it }
            val main = if (commentIdx >= 0) line.substring(0, commentIdx) else line
            val comment = if (commentIdx >= 0) line.substring(commentIdx) else null
            val tokenRe = Regex("(\"[^\"]*\"|'[^']*'|\\b\\w+\\b|\\S)")
            var pos = 0
            for (m in tokenRe.findAll(main)) {
                val tok = m.value
                val style = when {
                    tok.startsWith("\"") || tok.startsWith("'") -> SpanStyle(color = Color(0xFFCE9178))
                    tok in keywords -> SpanStyle(color = Color(0xFFC586C0))
                    tok.all { it.isDigit() || it == '.' } -> SpanStyle(color = Color(0xFFB5CEA8))
                    else -> SpanStyle(color = defaultColor)
                }
                withStyle(style) { append(tok) }
                pos = m.range.last + 1
            }
            if (comment != null) {
                withStyle(SpanStyle(color = Color(0xFF6A9955))) { append(comment) }
            }
        }
    }
}


@Composable
fun ClaudeCodeWelcome(
    chats: List<com.example.aichat.data.ChatSession>,
    onSelectChat: (com.example.aichat.data.ChatSession) -> Unit,
    modelName: String = "model",
    modifier: Modifier = Modifier
) {
    val recent = chats.sortedByDescending { it.updatedAt }.take(5)
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Welcome box — dashed border like Claude Code
        DashedBox {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Connector",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "model  $modelName",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF4EC9B0)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "нажмите / для ввода команды",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(0.45f)
                )
            }
        }

        // Recent activity
        DashedBox {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Recent activity",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(0.5f)
                )
                Spacer(Modifier.height(8.dp))
                if (recent.isEmpty()) {
                    Text(
                        "No chats yet",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(0.35f)
                    )
                } else {
                    recent.forEach { chat ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChat(chat) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "›",
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                chat.title,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${chat.messages.size} msg",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(0.35f)
                            )
                        }
                    }
                }
            }
        }

        // Tips
        DashedBox {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "What's new",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(0.5f)
                )
                Spacer(Modifier.height(8.dp))
                TipLine("/config", "API settings")
                TipLine("/skills", "skills + connectors")
                TipLine("/files", "artifacts")
                TipLine("/", "config files skills new clear export")
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "› type a message below to start",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(0.35f)
        )
    }
}

@Composable
private fun TipLine(cmd: String, desc: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            cmd,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 100.dp)
        )
        Text(
            desc,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(0.45f)
        )
    }
}

@Composable
fun DashedBox(content: @Composable () -> Unit) {
    val borderColor = MaterialTheme.colorScheme.outline.copy(0.7f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = stroke
                )
            }
            .padding(2.dp)
    ) {
        content()
    }
}

