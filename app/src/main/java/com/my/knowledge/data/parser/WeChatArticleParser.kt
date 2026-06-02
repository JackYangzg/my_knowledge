package com.my.knowledge.data.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class WeChatArticleParser(private val client: OkHttpClient = OkHttpClient()) {

    data class WeChatArticle(
        val title: String,
        val author: String,
        val contentHtml: String,
        val contentText: String,
        val originalUrl: String,
        val publishTime: String? = null
    )

    suspend fun parse(url: String): WeChatArticle = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G960U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Failed to fetch content")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.rich_media_title, h2.rich_media_title")?.text()?.trim() ?: doc.title()
        val author = doc.selectFirst("a#js_name, span.profile_nickname")?.text()?.trim() ?: "未知公众号"
        
        val contentElement = doc.selectFirst("div#js_content") ?: throw Exception("Content not found")
        
        // Handle images: WeChat uses data-src
        contentElement.select("img").forEach { img ->
            val dataSrc = img.attr("data-src")
            if (dataSrc.isNotEmpty()) {
                img.attr("src", dataSrc)
            }
        }

        val markdown = htmlToMarkdown(title, author, contentElement)

        WeChatArticle(
            title = title,
            author = author,
            contentHtml = contentElement.html(),
            contentText = markdown,
            originalUrl = url
        )
    }

    private fun htmlToMarkdown(title: String, author: String, contentElement: org.jsoup.nodes.Element): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("**作者:** ").append(author).append("\n\n")
        sb.append("---\n\n")

        contentElement.children().forEach { element ->
            when (element.tagName()) {
                "p" -> sb.append(element.text()).append("\n\n")
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = element.tagName().substring(1).toInt()
                    sb.append("#".repeat(level)).append(" ").append(element.text()).append("\n\n")
                }
                "img" -> {
                    val src = element.attr("src").ifEmpty { element.attr("data-src") }
                    if (src.isNotEmpty()) {
                        sb.append("![](").append(src).append(")\n\n")
                    }
                }
                "ul" -> {
                    element.select("li").forEach { li ->
                        sb.append("* ").append(li.text()).append("\n")
                    }
                    sb.append("\n")
                }
                "ol" -> {
                    element.select("li").forEachIndexed { index, li ->
                        sb.append("${index + 1}. ").append(li.text()).append("\n")
                    }
                    sb.append("\n")
                }
                else -> {
                    val text = element.text().trim()
                    if (text.isNotEmpty()) {
                        sb.append(text).append("\n\n")
                    }
                }
            }
        }
        return sb.toString()
    }
}
