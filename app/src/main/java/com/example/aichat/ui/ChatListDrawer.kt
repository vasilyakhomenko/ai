
package com.example.aichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aichat.data.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatListDrawerContent(
    chats: List<ChatSession>,
    currentChatId: String?,
    onSelect: (ChatSession) -> Unit,
    onNewChat: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenConnectors: () -> Unit = {},
    onOpenFiles: () -> Unit = {}
) {
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "› chats",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "+",
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                color = cs.primary,
                modifier = Modifier
                    .clickable { onNewChat() }
                    .padding(8.dp)
            )
        }
        HorizontalDivider(color = cs.outline.copy(0.3f))

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(chats, key = { it.id }) { chat ->
                val isSelected = chat.id == currentChatId
                if (renamingId == chat.id) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        trailingIcon = {
                            TextButton(onClick = {
                                onRename(chat.id, renameText)
                                renamingId = null
                            }) { Text("ok", fontFamily = FontFamily.Monospace) }
                        }
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(chat) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isSelected) "›" else "·",
                            fontFamily = FontFamily.Monospace,
                            color = if (isSelected) cs.primary else cs.onBackground.copy(0.3f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                chat.title,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${chat.messages.size} msg · ${SimpleDateFormat("d MMM", Locale("ru")).format(Date(chat.updatedAt))}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = cs.onSurface.copy(0.35f)
                            )
                        }
                        Text(
                            "x",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = cs.onBackground.copy(0.35f),
                            modifier = Modifier
                                .clickable { onDelete(chat.id) }
                                .padding(6.dp)
                        )
                    }
                }
            }
        }
    }
}
