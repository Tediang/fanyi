package com.tediang.quicktranslate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedServiceConfigStoreTest {
    @Test
    fun storesApiKeyEncryptedAndCanReadItBack() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = EncryptedServiceConfigStore(context, preferencesName = "encrypted-config-test")
        store.clear()

        val expected = ServiceConfig(
            name = "本机假服务",
            baseUrl = "https://api.example.com",
            apiKey = "secret-test-key",
            model = "test-model",
        )
        store.save(expected)

        assertEquals(expected, store.load())
        val persisted = context.getSharedPreferences("encrypted-config-test", 0).all.values.joinToString()
        assertFalse(persisted.contains("secret-test-key"))

        store.clear()
        assertNull(store.load())
    }
}
