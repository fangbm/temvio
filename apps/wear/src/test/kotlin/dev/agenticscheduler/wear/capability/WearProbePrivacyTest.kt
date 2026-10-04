package dev.agenticscheduler.wear.capability

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.application.sync.SecretReference
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WearProbePrivacyTest {
    @Test fun existingStructuredProbeHasOnlySyntheticBodyAndHttpsAuthHeader() = runTest {
        val credential = "credential-canary"
        val ref = "SecretRef-canary"
        val prohibited = listOf(credential, ref, "Task-canary", "Event-canary", "user-canary", "history-canary", "summary-canary", "audio-canary", "envelope-canary", "binding-digest-canary")
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("https", request.url.protocol.name); assertEquals("/v1/chat/completions", request.url.encodedPath)
            assertEquals("Bearer $credential", request.headers[HttpHeaders.Authorization])
            val body = (request.body as TextContent).text
            prohibited.forEach { assertFalse(body.contains(it), "private canary entered body") }
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("model", "messages", "tools", "stream"), json.keys)
            assertEquals("Call d9_capability_probe now.", json["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
            val function = json["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
            assertEquals("Return a structured function call.", function["description"]!!.jsonPrimitive.content)
            assertEquals(emptyMap(), function["parameters"]!!.jsonObject["properties"]!!.jsonObject)
            val name = function["name"]!!.jsonPrimitive.content
            respond("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"probe-only","type":"function","function":{"name":"$name","arguments":"{}"}}]}}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val config = ProviderConfig(ProviderConfigId("01900000-0000-7000-8000-000000000001"), "https://provider.example/v1", "explicit-model", 2048, 512, false, true, SecretReference(ref))
            val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { assertEquals(ref, it.value); credential })
            assertEquals(ProviderProbeResult.Supported, provider.probe(config)); assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun providerResponseSecretsAreDiscardedFromStatus() = runTest {
        val client = HttpClient(MockEngine { respond("response-secret-canary", HttpStatusCode.Unauthorized) })
        try {
            val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
            val config = ProviderConfig(ProviderConfigId("01900000-0000-7000-8000-000000000001"), "https://provider.example/v1", "model", 2048, 512, false, true, null)
            val result = provider.probe(config); assertEquals(WearProbeFailure.AUTHENTICATION, WearProviderProbe.classify(result))
            assertFalse(result.toString().contains("response-secret-canary"))
        } finally { client.close() }
    }
}
