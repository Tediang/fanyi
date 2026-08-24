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
    private var launch by mutableStateOf(TranslationLaunch.manual())
    private var clipboardResolutionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launch = TranslationLaunch.fromIntent(intent).also(LaunchPerformance::begin)
        setContent {
            QuickTranslateTheme {
                QuickTranslateApp(
                    profileRepository = profileRepository,
                    gateway = translationEngine,
                    connectionTester = connectionTester,
                    launch = launch,
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        clipboardResolutionId = null
        launch = TranslationLaunch.fromIntent(intent).also(LaunchPerformance::begin)
        resolveShortcutClipboardWhenFocused()
    }

    override fun onPostResume() {
        super.onPostResume()
        resolveShortcutClipboardWhenFocused()
    }

    private fun resolveShortcutClipboardWhenFocused() {
        val pending = launch
        if (pending.entry != TranslationEntry.CLIPBOARD_SHORTCUT || clipboardResolutionId == pending.id) return
        clipboardResolutionId = pending.id
        window.decorView.post {
            if (launch.id == pending.id) {
                val resolved = clipboardResolver.readAfterActivityResumed()
                LaunchPerformance.continueAs(pending.id, resolved)
                launch = resolved
            }
        }
    }
}
