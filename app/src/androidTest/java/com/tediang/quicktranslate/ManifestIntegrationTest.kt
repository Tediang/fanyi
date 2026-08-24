package com.tediang.quicktranslate

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test
    fun manifestDoesNotRequestHighPrivilegeCaptureOrOverlayCapabilities() {
        val requested = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        ).requestedPermissions.orEmpty().toSet()

        assertTrue("android.permission.INTERNET" in requested)
        assertFalse("android.permission.SYSTEM_ALERT_WINDOW" in requested)
        assertFalse("android.permission.BIND_ACCESSIBILITY_SERVICE" in requested)
        assertFalse("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" in requested)
        assertFalse("android.permission.CAPTURE_VIDEO_OUTPUT" in requested)
    }

    @Test
    fun translationWindowDoesNotResizeBehindTheSoftwareKeyboard() {
        val activity = packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }
}
