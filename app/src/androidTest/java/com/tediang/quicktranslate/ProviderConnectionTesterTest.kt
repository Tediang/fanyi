package com.tediang.quicktranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        server.enqueue(
            MockResponse.Builder().code(401)
                .body("{\"error\":{\"message\":\"bad key never-in-visible-error\"}}")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(400).body("{\"error\":{\"message\":\"model not found\"}}").build())
        server.enqueue(MockResponse.Builder().code(200).body("{\"unexpected\":true}").build())

        val url = tester.test(profile(allowCleartext = true)) as ConnectionTestResult.Failure
        val auth = tester.test(profile(allowCleartext = true)) as ConnectionTestResult.Failure
        val model = tester.test(profile(allowCleartext = true)) as ConnectionTestResult.Failure
        val protocol = tester.test(profile(allowCleartext = true)) as ConnectionTestResult.Failure

        assertEquals(ConnectionProblem.URL, url.problem)
        assertEquals(ConnectionProblem.AUTHENTICATION, auth.problem)
        assertTrue("Authentication diagnostics should retain the service port", auth.message.contains(":${server.port}"))
        assertFalse(auth.message.contains("never-in-visible-error"))
        assertEquals(ConnectionProblem.MODEL, model.problem)
        assertEquals(ConnectionProblem.PROTOCOL, protocol.problem)
    }

    @Test
    fun validatesResponsesAndAnthropicUsingTheirRealRequestSemantics() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],\"stop_reason\":\"end_turn\"}")
                .build(),
        )
        val tester = ProviderConnectionTester()
        val responses = profile(allowCleartext = true).copy(protocolType = ProtocolType.OPENAI_RESPONSES)
        val anthropic = profile(allowCleartext = true, apiKey = "anthropic-secret")
            .copy(protocolType = ProtocolType.ANTHROPIC_MESSAGES)

        assertTrue(tester.test(responses) is ConnectionTestResult.Success)
        assertTrue(tester.test(anthropic) is ConnectionTestResult.Success)

        val responsesRequest = server.takeRequest()
        val anthropicRequest = server.takeRequest()
        assertEquals("/v1/responses", responsesRequest.url.encodedPath)
        assertEquals("/v1/messages", anthropicRequest.url.encodedPath)
        assertEquals("anthropic-secret", anthropicRequest.headers["x-api-key"])
        assertEquals("2023-06-01", anthropicRequest.headers["anthropic-version"])
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
