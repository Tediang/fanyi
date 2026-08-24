package com.tediang.quicktranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderConnectionTesterTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun rejectsHttpBeforeNetworkUnlessProfileExplicitlyAllowsIt() = runBlocking {
        val blocked = profile(allowCleartext = false)

        val result = ProviderConnectionTester().test(blocked)

        assertEquals(ConnectionProblem.CLEARTEXT_BLOCKED, (result as ConnectionTestResult.Failure).problem)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun appliesSecretsAndRecognizesValidChatProtocol() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}")
                .build(),
        )
        val configured = profile(
            allowCleartext = true,
            apiKey = "fake-secret",
            customHeaders = listOf(CustomHeader("X-Route", "private-route")),
        )

        val result = ProviderConnectionTester().test(configured)

        assertTrue(result is ConnectionTestResult.Success)
        val request = server.takeRequest()
        assertEquals("Bearer fake-secret", request.headers["Authorization"])
        assertEquals("private-route", request.headers["X-Route"])
        assertEquals("/v1/chat/completions", request.url.encodedPath)
    }

    @Test
    fun distinguishesUrlAuthenticationModelAndProtocolFailures() = runBlocking {
        val tester = ProviderConnectionTester()
        server.enqueue(MockResponse.Builder().code(404).body("{\"error\":{\"message\":\"not found\"}}").build())
        server.enqueue(MockResponse.Builder().code(401).body("{\"error\":{\"message\":\"bad key\"}}").build())
        server.enqueue(MockResponse.Builder().code(400).body("{\"error\":{\"message\":\"model not found\"}}").build())
        server.enqueue(MockResponse.Builder().code(200).body("{\"unexpected\":true}").build())

        val url = tester.test(profile(allowCleartext = true)) as ConnectionTestResult.Failure
        val auth = tester.test(profile(allowCleartext = true)) as ConnectionTestResult.Failure
        val model = tester.test(profile(allowCleartext = true)) as ConnectionTestResult.Failure
        val protocol = tester.test(profile(allowCleartext = true)) as ConnectionTestResult.Failure

        assertEquals(ConnectionProblem.URL, url.problem)
        assertEquals(ConnectionProblem.AUTHENTICATION, auth.problem)
        assertEquals(ConnectionProblem.MODEL, model.problem)
        assertEquals(ConnectionProblem.PROTOCOL, protocol.problem)
    }

    private fun profile(
        allowCleartext: Boolean,
        apiKey: String = "",
        customHeaders: List<CustomHeader> = emptyList(),
    ) = ProviderProfile(
        name = "测试",
        protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
        baseUrl = server.url("/").toString(),
        apiKey = apiKey,
        model = "fake-model",
        customHeaders = customHeaders,
        allowCleartext = allowCleartext,
    )
}
