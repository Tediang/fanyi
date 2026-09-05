package com.tediang.quicktranslate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val profileRepository by lazy { ProviderProfileRepository(applicationContext) }
    private val translationEngine by lazy { TranslationEngine() }
    private val connectionTester by lazy { ProviderConnectionTester() }
    private val clipboardResolver by lazy { ClipboardShortcutResolver(applicationContext) }
    private val preferenceStore by lazy { TranslationPreferenceStore(applicationContext) }
    private var launch by mutableStateOf(TranslationLaunch.manual())
    private var clipboardResolutionScheduledId: String? = null
    private var clipboardResolutionCompletedId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launch = savedInstanceState?.restoredLaunch()
            ?: TranslationLaunch.fromIntent(intent).also(LaunchPerformance::begin)
        clipboardResolutionCompletedId = savedInstanceState?.getString(KEY_CLIPBOARD_RESOLUTION_COMPLETED_ID)
        setContent {
            QuickTranslateTheme {
                QuickTranslateApp(
                    profileRepository = profileRepository,
                    gateway = translationEngine,
                    connectionTester = connectionTester,
                    preferenceStore = preferenceStore,
                    launch = launch,
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) return
        clipboardResolutionScheduledId = null
        clipboardResolutionCompletedId = null
        launch = TranslationLaunch.fromIntent(intent).also(LaunchPerformance::begin)
        resolveShortcutClipboardWhenFocused()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_LAUNCH_ID, launch.id)
        outState.putString(KEY_LAUNCH_ENTRY, launch.entry.name)
        outState.putBoolean(KEY_LAUNCH_AUTO_TRANSLATE, launch.autoTranslate)
        outState.putBoolean(KEY_LAUNCH_READ_ONLY, launch.readOnlyFromHost)
        outState.putBoolean(KEY_LAUNCH_URL_ONLY, launch.urlOnly)
        outState.putString(KEY_CLIPBOARD_RESOLUTION_COMPLETED_ID, clipboardResolutionCompletedId)
        super.onSaveInstanceState(outState)
    }

    override fun onPostResume() {
        super.onPostResume()
        resolveShortcutClipboardWhenFocused()
    }

    private fun resolveShortcutClipboardWhenFocused() {
        val pending = launch
        if (
            pending.entry != TranslationEntry.CLIPBOARD_SHORTCUT ||
            clipboardResolutionScheduledId == pending.id ||
            clipboardResolutionCompletedId == pending.id
        ) return
        clipboardResolutionScheduledId = pending.id
        window.decorView.post {
            if (launch.id == pending.id) {
                val resolved = clipboardResolver.readAfterActivityResumed()
                LaunchPerformance.continueAs(pending.id, resolved)
                launch = resolved
                clipboardResolutionCompletedId = resolved.id
            }
            clipboardResolutionScheduledId = null
        }
    }

    private fun Bundle.restoredLaunch(): TranslationLaunch? {
        val id = getString(KEY_LAUNCH_ID) ?: return null
        val entry = runCatching {
            TranslationEntry.valueOf(getString(KEY_LAUNCH_ENTRY).orEmpty())
        }.getOrDefault(TranslationEntry.MANUAL)
        return TranslationLaunch(
            id = id,
            entry = entry,
            sourceText = "",
            autoTranslate = getBoolean(KEY_LAUNCH_AUTO_TRANSLATE),
            readOnlyFromHost = getBoolean(KEY_LAUNCH_READ_ONLY),
            urlOnly = getBoolean(KEY_LAUNCH_URL_ONLY),
        )
    }

    private companion object {
        const val KEY_LAUNCH_ID = "launch_id"
        const val KEY_LAUNCH_ENTRY = "launch_entry"
        const val KEY_LAUNCH_AUTO_TRANSLATE = "launch_auto_translate"
        const val KEY_LAUNCH_READ_ONLY = "launch_read_only"
        const val KEY_LAUNCH_URL_ONLY = "launch_url_only"
        const val KEY_CLIPBOARD_RESOLUTION_COMPLETED_ID = "clipboard_resolution_completed_id"
    }
}
