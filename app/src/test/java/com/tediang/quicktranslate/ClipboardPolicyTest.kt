package com.tediang.quicktranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPolicyTest {
    @Test
    fun acceptsFreshTextThenRejectsSameContentFingerprint() {
        val first = ClipboardPolicy.decide(
            text = "  Hello  ",
            isTextMime = true,
            timestampMillis = 900_000,
            nowMillis = 1_000_000,
            consumedFingerprint = null,
        ) as ClipboardDecision.Translate

        assertEquals("Hello", first.text)
        val repeated = ClipboardPolicy.decide(
            text = "Hello",
            isTextMime = true,
            timestampMillis = 999_000,
            nowMillis = 1_000_000,
            consumedFingerprint = first.fingerprint,
        )
        assertEquals(
            ClipboardRejection.ALREADY_CONSUMED,
            (repeated as ClipboardDecision.ManualInput).reason,
        )
    }

    @Test
    fun rejectsExpiredUnknownAgeEmptyAndNonTextClips() {
        assertManual(ClipboardRejection.EXPIRED, "old", true, 1L, 200_000L)
        assertManual(ClipboardRejection.UNKNOWN_AGE, "unknown", true, 0L, 1_000L)
        assertManual(ClipboardRejection.EMPTY, " ", true, 900L, 1_000L)
        assertManual(ClipboardRejection.NON_TEXT, "content", false, 900L, 1_000L)
    }

    @Test
    fun defaultDirectionUsesChinesePresenceOnlyForTargetChoice() {
        assertEquals(TargetLanguage.ENGLISH, defaultTargetLanguage("电池 stores energy"))
        assertEquals(TargetLanguage.SIMPLIFIED_CHINESE, defaultTargetLanguage("Battery stores energy"))
        assertTrue(defaultTargetLanguage("") == TargetLanguage.SIMPLIFIED_CHINESE)
    }

    private fun assertManual(
        expected: ClipboardRejection,
        text: String,
        textMime: Boolean,
        timestamp: Long,
        now: Long,
    ) {
        val result = ClipboardPolicy.decide(text, textMime, timestamp, now, null)
        assertEquals(expected, (result as ClipboardDecision.ManualInput).reason)
    }
}
