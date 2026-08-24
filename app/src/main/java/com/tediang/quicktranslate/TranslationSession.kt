package com.tediang.quicktranslate

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

internal const val ACTION_TRANSLATE_CLIPBOARD =
    "com.tediang.quicktranslate.action.TRANSLATE_CLIPBOARD"

internal enum class TranslationEntry(val title: String) {
    MANUAL("快译"),
    PROCESS_TEXT("选词翻译"),
    SHARE("分享翻译"),
    CLIPBOARD_SHORTCUT("快捷键翻译"),
}

internal data class TranslationLaunch(
    val id: String = UUID.randomUUID().toString(),
    val entry: TranslationEntry,
    val sourceText: String,
    val autoTranslate: Boolean,
    val focusInput: Boolean,
    val readOnlyFromHost: Boolean = false,
    val urlOnly: Boolean = false,
) {
    companion object {
        fun manual() = TranslationLaunch(
            entry = TranslationEntry.MANUAL,
            sourceText = "",
            autoTranslate = false,
            focusInput = false,
        )

        fun fromIntent(intent: Intent?): TranslationLaunch = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                TranslationLaunch(
                    entry = TranslationEntry.PROCESS_TEXT,
                    sourceText = text,
                    autoTranslate = text.isNotBlank(),
                    focusInput = text.isBlank(),
                    readOnlyFromHost = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
                )
            }
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                TranslationLaunch(
                    entry = TranslationEntry.SHARE,
                    sourceText = text,
                    autoTranslate = text.isNotBlank(),
                    focusInput = text.isBlank(),
                    readOnlyFromHost = true,
                    urlOnly = text.isHttpUrlOnly(),
                )
            }
            ACTION_TRANSLATE_CLIPBOARD -> TranslationLaunch(
                entry = TranslationEntry.CLIPBOARD_SHORTCUT,
                sourceText = "",
                autoTranslate = false,
                focusInput = true,
            )
            else -> manual()
        }
    }
}

internal sealed interface TranslationProgress {
    data object Idle : TranslationProgress
    data object Running : TranslationProgress
    data class Completed(val diagnostics: TranslationDiagnostics) : TranslationProgress
    data class Failed(
        val type: TranslationErrorType,
        val message: String,
        val incomplete: Boolean,
        val diagnostics: TranslationDiagnostics?,
    ) : TranslationProgress
    data object Cancelled : TranslationProgress
}

internal data class TranslationSessionState(
    val sourceText: String,
    val targetLanguage: TargetLanguage,
    val preference: TranslationPreference = TranslationPreference.GENERAL,
    val translatedText: String = "",
    val progress: TranslationProgress = TranslationProgress.Idle,
)

internal class TranslationSessionController(
    private val gateway: TranslationGateway,
    private val scope: CoroutineScope,
    initialSourceText: String,
    private val launchId: String? = null,
    initialPreference: TranslationPreference = TranslationPreference.GENERAL,
) {
    private val mutableState = MutableStateFlow(
        TranslationSessionState(
            sourceText = initialSourceText,
            targetLanguage = defaultTargetLanguage(initialSourceText),
            preference = initialPreference,
        ),
    )
    val state: StateFlow<TranslationSessionState> = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var requestId = 0L
    private var targetManuallySelected = false

    fun updateSource(text: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            sourceText = text,
            targetLanguage = if (targetManuallySelected) current.targetLanguage else defaultTargetLanguage(text),
            translatedText = if (current.progress is TranslationProgress.Running) current.translatedText else "",
            progress = if (current.progress is TranslationProgress.Running) current.progress else TranslationProgress.Idle,
        )
    }

    fun selectTarget(targetLanguage: TargetLanguage) {
        targetManuallySelected = true
        val current = mutableState.value
        mutableState.value = current.copy(
            targetLanguage = targetLanguage,
            translatedText = if (current.progress is TranslationProgress.Running) current.translatedText else "",
            progress = if (current.progress is TranslationProgress.Running) current.progress else TranslationProgress.Idle,
        )
    }

    fun selectPreference(preference: TranslationPreference) {
        val current = mutableState.value
        mutableState.value = current.copy(
            preference = preference,
            translatedText = if (current.progress is TranslationProgress.Running) current.translatedText else "",
            progress = if (current.progress is TranslationProgress.Running) current.progress else TranslationProgress.Idle,
        )
    }

    fun start(profile: ProviderProfile) {
        val source = mutableState.value.sourceText.trim()
        if (source.isEmpty()) {
            mutableState.value = mutableState.value.copy(
                progress = TranslationProgress.Failed(
                    TranslationErrorType.CONFIGURATION,
                    "请输入要翻译的原文",
                    incomplete = false,
                    diagnostics = null,
                ),
            )
            return
        }
        val id = ++requestId
        activeJob?.cancel()
        mutableState.value = mutableState.value.copy(
            sourceText = source,
            translatedText = "",
            progress = TranslationProgress.Running,
        )
        val target = mutableState.value.targetLanguage
        val preference = mutableState.value.preference
        activeJob = scope.launch {
            try {
                val result = gateway.translate(
                    profile = profile,
                    sourceText = source,
                    targetLanguage = target,
                    preference = preference,
                    onRequestDispatched = { launchId?.let(LaunchPerformance::markRequestDispatched) },
                ) { delta ->
                    if (requestId == id) {
                        mutableState.value = mutableState.value.copy(
                            translatedText = mutableState.value.translatedText + delta,
                        )
                    }
                }
                if (requestId == id) {
                    launchId?.let { LaunchPerformance.markTranslationFinished(it, result.diagnostics) }
                    mutableState.value = mutableState.value.copy(
                        translatedText = result.text,
                        progress = TranslationProgress.Completed(result.diagnostics),
                    )
                }
            } catch (_: CancellationException) {
                if (requestId == id) {
                    mutableState.value = mutableState.value.copy(progress = TranslationProgress.Cancelled)
                }
            } catch (error: TranslationFailure) {
                if (requestId == id) {
                    error.diagnostics?.let { diagnostics ->
                        launchId?.let { LaunchPerformance.markTranslationFinished(it, diagnostics) }
                    }
                    val partial = error.partialText.ifBlank { mutableState.value.translatedText }
                    mutableState.value = mutableState.value.copy(
                        translatedText = partial,
                        progress = TranslationProgress.Failed(
                            type = error.type,
                            message = error.userMessage,
                            incomplete = partial.isNotBlank(),
                            diagnostics = error.diagnostics,
                        ),
                    )
                }
            } catch (error: Exception) {
                if (requestId == id) {
                    mutableState.value = mutableState.value.copy(
                        progress = TranslationProgress.Failed(
                            TranslationErrorType.NETWORK,
                            error.message ?: "翻译失败",
                            incomplete = mutableState.value.translatedText.isNotBlank(),
                            diagnostics = null,
                        ),
                    )
                }
            }
        }
    }

    fun cancel() {
        requestId += 1
        activeJob?.cancel()
        activeJob = null
        mutableState.value = mutableState.value.copy(progress = TranslationProgress.Cancelled)
    }

    fun clearSource() {
        stopActiveRequest()
        mutableState.value = mutableState.value.copy(
            sourceText = "",
            translatedText = "",
            progress = TranslationProgress.Idle,
        )
    }

    fun clearTranslation() {
        stopActiveRequest()
        mutableState.value = mutableState.value.copy(
            translatedText = "",
            progress = TranslationProgress.Idle,
        )
    }

    fun dispose() {
        stopActiveRequest()
    }

    private fun stopActiveRequest() {
        requestId += 1
        activeJob?.cancel()
        activeJob = null
    }
}

internal enum class ClipboardRejection {
    EMPTY,
    NON_TEXT,
    UNKNOWN_AGE,
    EXPIRED,
    ALREADY_CONSUMED,
}

internal sealed interface ClipboardDecision {
    data class Translate(val text: String, val fingerprint: String) : ClipboardDecision
    data class ManualInput(val reason: ClipboardRejection) : ClipboardDecision
}

internal object ClipboardPolicy {
    fun decide(
        text: String?,
        isTextMime: Boolean,
        timestampMillis: Long,
        nowMillis: Long,
        consumedFingerprint: String?,
    ): ClipboardDecision {
        if (!isTextMime) return ClipboardDecision.ManualInput(ClipboardRejection.NON_TEXT)
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) return ClipboardDecision.ManualInput(ClipboardRejection.EMPTY)
        if (timestampMillis <= 0L) return ClipboardDecision.ManualInput(ClipboardRejection.UNKNOWN_AGE)
        if (nowMillis - timestampMillis !in 0..CLIPBOARD_FRESHNESS_MILLIS) {
            return ClipboardDecision.ManualInput(ClipboardRejection.EXPIRED)
        }
        val fingerprint = sha256(normalized)
        if (fingerprint == consumedFingerprint) {
            return ClipboardDecision.ManualInput(ClipboardRejection.ALREADY_CONSUMED)
        }
        return ClipboardDecision.Translate(normalized, fingerprint)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val CLIPBOARD_FRESHNESS_MILLIS = 2 * 60 * 1_000L
}

internal class ClipboardShortcutResolver(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readAfterActivityResumed(nowMillis: Long = System.currentTimeMillis()): TranslationLaunch {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val description = clipboard.primaryClipDescription
        val clip = clipboard.primaryClip
        val isText = description?.hasMimeType("text/*") == true
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        return when (
            val decision = ClipboardPolicy.decide(
                text = text,
                isTextMime = isText,
                timestampMillis = description?.timestamp ?: 0L,
                nowMillis = nowMillis,
                consumedFingerprint = preferences.getString(KEY_CONSUMED_FINGERPRINT, null),
            )
        ) {
            is ClipboardDecision.Translate -> {
                preferences.edit().putString(KEY_CONSUMED_FINGERPRINT, decision.fingerprint).apply()
                TranslationLaunch(
                    entry = TranslationEntry.CLIPBOARD_SHORTCUT,
                    sourceText = decision.text,
                    autoTranslate = true,
                    focusInput = false,
                )
            }
            is ClipboardDecision.ManualInput -> TranslationLaunch(
                entry = TranslationEntry.CLIPBOARD_SHORTCUT,
                sourceText = "",
                autoTranslate = false,
                focusInput = true,
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "quick_translate_clipboard_shortcut"
        const val KEY_CONSUMED_FINGERPRINT = "consumed_fingerprint"
    }
}

private fun String.isHttpUrlOnly(): Boolean {
    val candidate = trim()
    if (candidate.isEmpty() || candidate.any(Char::isWhitespace)) return false
    val uri = Uri.parse(candidate)
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}
