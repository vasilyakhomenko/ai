package com.example.aichat

import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.core.app.ActivityCompat
import com.example.aichat.data.ReplyNotifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichat.ui.*
import com.example.aichat.ui.theme.AIChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReplyNotifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
        setContent {
            val vm: ChatViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            AIChatTheme(themeMode = themeMode) {
                Surface(Modifier.fillMaxSize()) {
                    val setupDone by vm.setupDone.collectAsState()
                    var overlay by remember { mutableStateOf<String?>(null) }

                    when {
                        setupDone == null -> { /* loading */ }
                        setupDone == false -> OnboardingScreen(vm) { }
                        overlay == "settings" -> SettingsScreen(vm) { overlay = null }
                        overlay == "skills" -> SkillsScreen(vm) { overlay = null }
                        overlay == "files" -> FilesScreen(vm) { overlay = null }
                        else -> ChatScreen(
                            viewModel = vm,
                            onOpenSettings = { overlay = "settings" },
                            onOpenSkills = { overlay = "skills" },
                            onOpenFiles = { overlay = "files" }
                        )
                    }
                }
            }
        }
    }
}
