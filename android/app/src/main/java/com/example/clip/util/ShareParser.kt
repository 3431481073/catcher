package com.example.clip.util

/**
 * 解析 ACTION_SEND 传入的分享文本。
 * 通常是「标题 + 链接」混合格式：提取 URL 作为视频链接，链接前部分清理后作 title。
 * 提取不到 URL 时返回空 url，由调用方当纯文本处理。
 */
object ShareParser {

    private val URL_REGEX = Regex("""https?://[^\s]+""")

    data class Parsed(val url: String?, val title: String?)

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        val match = URL_REGEX.find(text)
            ?: return Parsed(url = null, title = null)

        val url = match.value
            .trimEnd(
                '。', '，', ',', '；', ';', '、', '）', ')', '】', ']', '}', '>',
                '？', '?', '！', '!', '…', '“', '”', '"', '\'', '.', '：', ':'
            )
        val title = text.substring(0, match.range.first)
            .replace("【", "").replace("】", "")
            .replace("[", "").replace("]", "")
            .trim()
            .takeIf { it.isNotBlank() }

        return Parsed(url = url, title = title)
    }
}
