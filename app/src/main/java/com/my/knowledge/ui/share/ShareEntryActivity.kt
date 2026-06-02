package com.my.knowledge.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.my.knowledge.ui.KnowledgeManager

class ShareEntryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // KnowledgeManager must be initialized
        KnowledgeManager.init(applicationContext)

        if (intent?.action == Intent.ACTION_SEND) {
            handleSendIntent(intent)
        } else {
            finish()
        }
    }

    private fun handleSendIntent(intent: Intent) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText == null) {
            finish()
            return
        }

        if (isWeChatArticleUrl(sharedText)) {
            val url = extractUrlFromText(sharedText)
            processWeChatUrl(url)
        } else {
            // Generic text or link
            val url = extractUrlFromText(sharedText)
            if (url.startsWith("http")) {
                // If it's a generic link, maybe we want to parse it too later
                // For now just save as generic text
                saveGenericText(sharedText)
            } else {
                saveGenericText(sharedText)
            }
        }
    }

    private fun isWeChatArticleUrl(text: String): Boolean {
        return text.contains("mp.weixin.qq.com") || text.contains("weixin.qq.com")
    }

    private fun extractUrlFromText(text: String): String {
        val urlRegex = "(https?://[^\\s]+)".toRegex()
        return urlRegex.find(text)?.value ?: text
    }

    private fun processWeChatUrl(url: String) {
        Toast.makeText(this, "正在从微信导入...", Toast.LENGTH_SHORT).show()
        KnowledgeManager.importFromWeChat(url)
        finish()
    }

    private fun saveGenericText(text: String) {
        val title = text.take(20).replace("\n", " ") + "..."
        KnowledgeManager.importAndAnalyze(
            name = title,
            type = "shared_text",
            content = text,
            targetLibrary = "unfiled"
        )
        Toast.makeText(this, "已保存到未归类", Toast.LENGTH_SHORT).show()
        finish()
    }
}
