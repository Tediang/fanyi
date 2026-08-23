package com.tediang.quicktranslate

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageManager = context.packageManager

    @Test
    fun selectedTextAndTextShareResolveToQuickTranslate() {
        val processTextHandlers = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
        val sendHandlers = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("text/plain"),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )

        val processTextHandler = processTextHandlers.single {
            it.activityInfo.packageName == context.packageName
        }
        assertEquals("快译", processTextHandler.loadLabel(packageManager).toString())
        assertTrue(sendHandlers.any { it.activityInfo.packageName == context.packageName })
    }

    @Test
    fun clipboardTranslationIsPublishedAsStaticShortcut() {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val shortcut = shortcutManager.manifestShortcuts.single {
            it.id == "translate_clipboard"
        }

        assertEquals("翻译剪贴板", shortcut.shortLabel.toString())
        assertEquals(
            "com.tediang.quicktranslate.action.TRANSLATE_CLIPBOARD",
            shortcut.intent?.action,
        )
    }
}
