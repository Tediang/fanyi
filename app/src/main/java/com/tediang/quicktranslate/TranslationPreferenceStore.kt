package com.tediang.quicktranslate

import android.content.Context

internal class TranslationPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TranslationPreference = runCatching {
        TranslationPreference.valueOf(
            preferences.getString(KEY_PREFERENCE, TranslationPreference.GENERAL.name)
                ?: TranslationPreference.GENERAL.name,
        )
    }.getOrDefault(TranslationPreference.GENERAL)

    fun save(preference: TranslationPreference) {
        preferences.edit().putString(KEY_PREFERENCE, preference.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "quick_translate_preferences"
        const val KEY_PREFERENCE = "translation_preference"
    }
}
