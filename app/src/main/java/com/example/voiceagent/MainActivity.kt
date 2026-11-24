package com.example.voiceagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.voiceagent.ui.AgentApp
import com.example.voiceagent.ui.theme.VoiceAgentTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VoiceAgentTheme {
                AgentApp(viewModel)
            }
        }
    }
}
