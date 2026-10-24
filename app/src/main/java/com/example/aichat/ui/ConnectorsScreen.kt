package com.example.aichat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aichat.data.Connector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val connectors by viewModel.connectors.collectAsState()
    var showForm by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("connectors", fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showForm = !showForm }) {
                        Icon(Icons.Default.Add, "Добавить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                "Коннекторы ≈ плагины Claude Code.\nОписывают внешние сервисы; модель учитывает их в ответах.",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(0.55f),
                lineHeight = 17.sp
            )

            if (showForm) {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Имя") },
                        placeholder = { Text("Notion / GitHub / Weather") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("Описание") },
                        placeholder = { Text("Что умеет этот сервис") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Base URL (необязательно)") },
                        placeholder = { Text("https://api.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                    OutlinedTextField(
                        value = auth, onValueChange = { auth = it },
                        label = { Text("Auth header (необязательно)") },
                        placeholder = { Text("Bearer sk-...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                    OutlinedTextField(
                        value = instructions, onValueChange = { instructions = it },
                        label = { Text("Инструкции для модели") },
                        placeholder = { Text("Как использовать этот API…") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                    Button(
                        onClick = {
                            if (name.isBlank()) return@Button
                            viewModel.addConnector(
                                Connector(
                                    name = name.trim(),
                                    description = description.trim().ifBlank { "Коннектор $name" },
                                    baseUrl = baseUrl.trim(),
                                    authHeader = auth.trim(),
                                    instructions = instructions.trim()
                                )
                            )
                            name = ""; description = ""; baseUrl = ""; auth = ""; instructions = ""
                            showForm = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = name.isNotBlank()
                    ) {
                        Text("save connector", fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "installed (${connectors.size})",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
            )
            Spacer(Modifier.height(6.dp))

            if (connectors.isEmpty()) {
                Text(
                    "no connectors — tap + to add",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.35f)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(connectors, key = { it.id }) { c ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                    Text(
                                        c.description,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                                    )
                                    if (c.baseUrl.isNotBlank()) {
                                        Text(
                                            c.baseUrl,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary.copy(0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Switch(
                                    checked = c.enabled,
                                    onCheckedChange = { viewModel.toggleConnector(c.id, it) }
                                )
                                IconButton(
                                    onClick = { viewModel.deleteConnector(c.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
