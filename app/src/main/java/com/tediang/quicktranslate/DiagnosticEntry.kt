package com.tediang.quicktranslate

import android.content.Intent
import android.net.Uri

private const val DIAGNOSTIC_ACTION_TRANSLATE_CLIPBOARD =
    "com.tediang.quicktranslate.action.TRANSLATE_CLIPBOARD"

internal data class DiagnosticEntry(
    val title: String,
    val text: String,
    val action: String,
    val mimeType: String,
    val textMode: String,
    val contentKind: String,
) {
    companion object {
        fun empty() = DiagnosticEntry(
            title = "普通启动",
            text = "未收到外部文本",
            action = Intent.ACTION_MAIN,
            mimeType = "—",
            textMode = "—",
            contentKind = "无",
        )

        fun from(intent: Intent?): DiagnosticEntry {
            if (intent?.action == DIAGNOSTIC_ACTION_TRANSLATE_CLIPBOARD) {
                return DiagnosticEntry(
                    title = "快捷键翻译",
                    text = "诊断版未读取剪贴板",
                    action = DIAGNOSTIC_ACTION_TRANSLATE_CLIPBOARD,
                    mimeType = "—",
                    textMode = "待接入",
                    contentKind = "剪贴板文本",
                )
            }

            if (intent?.action == Intent.ACTION_SEND) {
                val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                return DiagnosticEntry(
                    title = "分享翻译",
                    text = sharedText,
                    action = Intent.ACTION_SEND,
                    mimeType = intent.type ?: "—",
                    textMode = "只读",
                    contentKind = if (sharedText.isHttpUrlOnly()) {
                        "URL（不会抓取）"
                    } else {
                        "文字"
                    },
                )
            }

            if (intent?.action != Intent.ACTION_PROCESS_TEXT) return empty()

            val readonly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            return DiagnosticEntry(
                title = "选词翻译",
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
                action = Intent.ACTION_PROCESS_TEXT,
                mimeType = intent.type ?: "—",
                textMode = if (readonly) "只读" else "可编辑",
                contentKind = "文字",
            )
        }
    }
}

private fun String.isHttpUrlOnly(): Boolean {
    val candidate = trim()
    if (candidate.isEmpty() || candidate.any(Char::isWhitespace)) return false
    val uri = Uri.parse(candidate)
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}
