package com.example.aichat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: ChatViewModel,
    onDone: () -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var baseUrl by remember { mutableStateOf("https://api.claudech.com/v1") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("claude-opus-5-5") }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            "Connector",
            fontFamily = FontFamily.Monospace,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = cs.primary
        )
        Text(
            "console · openai-compatible",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = cs.onBackground.copy(0.45f)
        )
        Spacer(Modifier.height(28.dp))

        Text(
            "› setup",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = cs.onBackground.copy(0.5f)
        )
        Spacer(Modifier.height(16.dp))

        when (step) {
            0 -> {
                Text("base url", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.primary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text("next ›", fontFamily = FontFamily.Monospace)
                }
            }
            1 -> {
                Text("api key", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.primary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) {
                    Text("next ›", fontFamily = FontFamily.Monospace)
                }
            }
            else -> {
                Text("model", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.primary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.saveSettings(baseUrl, apiKey, model, null)
                            viewModel.markSetupDone()
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("start ›", fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "press / later for commands",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = cs.onBackground.copy(0.35f)
        )
    }
}
