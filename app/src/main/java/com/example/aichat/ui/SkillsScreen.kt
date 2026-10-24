package com.example.aichat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aichat.data.Skill
import com.example.aichat.data.SkillInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val skills by viewModel.skills.collectAsState()
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) } // 0 skills, 1 connectors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val connectors by viewModel.connectors.collectAsState()

    val localPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        message = null
        error = null
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                } ?: throw Exception("read failed")
                val result = SkillInstaller.parseSkillMdPublic(text, uri.toString())
                result.onSuccess {
                    viewModel.installSkillDirect(it)
                    message = "installed: ${it.name}"
                }.onFailure { error = it.message }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (tab == 0) "/skills" else "/skills · connectors",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = { Text("skills", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    label = { Text("connectors", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                )
            }
            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                Text(
                    "› install SKILL.md from phone or GitHub",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { localPicker.launch("*/*") },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("› open local file", fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("https://…/SKILL.md", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (url.isBlank() || loading) return@Button
                        loading = true
                        message = null
                        error = null
                        viewModel.installSkillFromUrl(url) { result ->
                            loading = false
                            result.onSuccess {
                                message = "installed: ${it.name}"
                                url = ""
                            }.onFailure { error = it.message }
                        }
                    },
                    enabled = url.isNotBlank() && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "…" else "› install from github", fontFamily = FontFamily.Monospace)
                }
                message?.let {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                error?.let {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "installed (${skills.size})",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(skills, key = { it.id }) { skill ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("›", color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(skill.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                    Text(
                                        skill.description,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                                    )
                                }
                                Switch(checked = skill.enabled, onCheckedChange = {
                                    viewModel.toggleSkill(skill.id, it)
                                })
                                IconButton(onClick = { viewModel.deleteSkill(skill.id) }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                // Simple connector list + hint to use connectors screen logic via ViewModel
                Text(
                    "› connectors act as plugin descriptions in the system prompt",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                )
                Spacer(Modifier.height(10.dp))
                if (connectors.isEmpty()) {
                    Text(
                        "no connectors — add via code or future /connectors form",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.35f)
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(connectors, key = { it.id }) { c ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("›", color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                    Text(
                                        c.description,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                                    )
                                }
                                Switch(checked = c.enabled, onCheckedChange = {
                                    viewModel.toggleConnector(c.id, it)
                                })
                                IconButton(onClick = { viewModel.deleteConnector(c.id) }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
