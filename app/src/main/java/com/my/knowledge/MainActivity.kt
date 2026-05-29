package com.my.knowledge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.my.knowledge.ui.KnowledgeApp
import com.my.knowledge.ui.KnowledgeManager
import com.my.knowledge.ui.theme.My_knowledgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize the persistence layer (JSON DB + File System)
        KnowledgeManager.init(this)

        setContent {
            My_knowledgeTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    KnowledgeApp()
                }
            }
        }
    }
}
