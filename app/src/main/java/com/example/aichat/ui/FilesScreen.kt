package com.example.aichat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import android.widget.Toast
import com.example.aichat.data.Artifact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val artifacts by viewModel.artifacts.collectAsState()
    var selected by remember { mutableStateOf<Artifact?>(null) }

    if (selected != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(selected!!.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1)
                    },
                    navigationIcon = {
                        IconButton(onClick = { selected = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    },
                    actions = {
                        val ctx = LocalContext.current
                        TextButton(onClick = {
                            try {
                                val art = selected!!
                                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                                    ?: ctx.filesDir
                                val f = File(dir, art.name)
                                f.writeText(art.content)
                                Toast.makeText(ctx, "Сохранено: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("↓", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        IconButton(onClick = {
                            viewModel.deleteArtifact(selected!!.id)
                            selected = null
                        }) {
                            Icon(Icons.Default.Close, "Удалить")
                        }
                    }
                )
            }
        ) { padding ->
            val content = selected!!.content
            val isSvg = selected!!.name.endsWith(".svg", ignoreCase = true) ||
                content.trimStart().startsWith("<svg", ignoreCase = true) ||
                content.contains("<svg", ignoreCase = true)
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (isSvg) {
                    val svgBody = if (content.contains("<svg", ignoreCase = true)) {
                        val start = content.indexOf("<svg", ignoreCase = true)
                        val end = content.indexOf("</svg>", ignoreCase = true)
                        if (end > start) content.substring(start, end + 6) else content
                    } else content
                    SvgImage(svgBody)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "svg source",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    content,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("/files", fontFamily = FontFamily.Monospace, fontSize = 15.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (artifacts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "› no artifacts yet\n  code blocks from replies appear here",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(artifacts, key = { it.id }) { a ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(0.45f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.clickable { selected = a }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                Text(
                                    "${a.language} · ${SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(a.createdAt))}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
