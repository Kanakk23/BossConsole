package ai.rever.boss.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSharingBoundaryTest {
    @Test fun `viewer cannot publish or change its admitted identity`() {
        val scope = AppSharingPeerScope("session", "generation", "viewer", false)
        val subscribe =
            buildJsonObject {
                put("action", "mediaSubscribe")
                put("sdp", "offer")
            }
        val bound = scope.bind(subscribe)
        assertEquals("session", bound.getValue("session_id").jsonPrimitive.content)
        assertEquals("generation", bound.getValue("generation").jsonPrimitive.content)
        assertEquals("viewer", bound.getValue("peer_id").jsonPrimitive.content)
        assertEquals("offer", bound.getValue("sdp").jsonPrimitive.content)
        val hostActions =
            listOf("mediaPublish", "dataPublish", "controlPoll", "mediaDemand", "register", "stop", "preferencesSet")
        hostActions.forEach { action ->
            assertFailsWith<IllegalArgumentException> { scope.bind(buildJsonObject { put("action", action) }) }
        }
        listOf("session_id", "generation", "peer_id").forEach { field ->
            assertFailsWith<IllegalArgumentException> {
                scope.bind(
                    buildJsonObject {
                        put("action", "controlAcquire")
                        put(field, "another-peer")
                    },
                )
            }
        }
        assertEquals(bound, scope.bind(bound))
    }

    @Test fun `window page cannot mutate another selected window`() {
        listOf(true, false).forEach { publisher ->
            val peer = AppSharingPeerScope("session", "generation", "peer", publisher, "selected")
            val request = buildJsonObject { put("action", "mediaCreate") }
            assertEquals(
                "selected",
                peer
                    .bind(request)
                    .getValue("window_id")
                    .jsonPrimitive.content,
            )
            assertFailsWith<IllegalArgumentException> {
                peer.bind(
                    buildJsonObject {
                        put("action", "mediaCreate")
                        put("window_id", "unselected")
                    },
                )
            }
        }
    }

    @Test fun `host cannot acquire viewer privileges and control poll pins host identity`() {
        val host = AppSharingPeerScope("session", "generation", "host", true)
        listOf("controlAcquire", "controlRenew", "mediaSubscribe", "dataSubscribe", "admit").forEach { action ->
            assertFailsWith<IllegalArgumentException> { host.bind(buildJsonObject { put("action", action) }) }
        }
        assertEquals(
            "host",
            host
                .bind(buildJsonObject { put("action", "controlPoll") })
                .getValue("host_peer_id")
                .jsonPrimitive.content,
        )
        assertFailsWith<IllegalArgumentException> {
            host.bind(
                buildJsonObject {
                    put("action", "controlPoll")
                    put("host_peer_id", "other-host")
                },
            )
        }
    }

    @Test fun `loopback requires exact origin and page token and retirement removes authority`() {
        AppSharingAssets().use { server ->
            val calls = AtomicInteger()
            val page =
                server.open(buildJsonObject {}, false) {
                    calls.incrementAndGet()
                    buildJsonObject { put("ok", true) }
                }
            val uri = URI(page.url)
            val origin = "${uri.scheme}://${uri.authority}"
            val token = uri.path.split('/')[1]
            val rpc = uri.resolve("rpc")
            listOf(
                null to token,
                "https://attacker.invalid" to token,
                origin to null,
                origin to "wrong-token",
            ).forEach { (from, secret) ->
                assertEquals(403, post(rpc, from, secret).statusCode())
            }
            assertEquals(0, calls.get())
            assertEquals(200, post(rpc, origin, token).statusCode())
            assertEquals(1, calls.get())
            page.close()
            assertEquals(404, get(uri).statusCode())
            assertEquals(404, post(rpc, origin, token).statusCode())
            assertEquals(1, calls.get())
        }
    }

    @Test fun `bootstrap escapes script termination while preserving config and restrictive headers`() {
        AppSharingAssets().use { server ->
            val injected = "</script><script>alert('unsafe')</script>\u2028\u2029"
            val page = server.open(buildJsonObject { put("title", injected) }, true) { buildJsonObject {} }
            val response = get(URI(page.url))
            assertEquals(200, response.statusCode())
            assertFalse(response.body().contains(injected))
            assertFalse(response.body().contains("<script>alert('unsafe')"))
            val bootstrap = response.body().substringAfter("window.__bossAppShareConfig=").substringBefore(";</script>")
            assertEquals(
                injected,
                Json
                    .parseToJsonElement(bootstrap)
                    .jsonObject
                    .getValue("title")
                    .jsonPrimitive.content,
            )
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow())
            assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow())
            val policy = response.headers().firstValue("Content-Security-Policy").orElseThrow()
            assertTrue(policy.contains("frame-ancestors 'none'"))
            assertTrue(policy.contains("connect-src 'self'"))
            assertEquals(404, get(URI(page.url).resolve("viewer.html")).statusCode())
        }
    }

    @Test fun `safe backend errors survive but arbitrary error details are not returned`() {
        AppSharingAssets().use { server ->
            listOf(
                "approval_required" to 403,
                "account_changed" to 401,
                "conflict" to 409,
                "publication_pending" to 409,
                "sfu_not_configured" to 503,
                "secret token = bad<script>" to 503,
            ).forEach { (reason, status) ->
                val page = server.open(buildJsonObject {}, false) { throw AppSharingException(reason) }
                val uri = URI(page.url)
                val response = post(uri.resolve("rpc"), "${uri.scheme}://${uri.authority}", uri.path.split('/')[1])
                assertEquals(status, response.statusCode())
                val expected = if (reason.startsWith("secret")) "request_failed" else reason
                assertEquals(
                    expected,
                    Json
                        .parseToJsonElement(response.body())
                        .jsonObject
                        .getValue("error")
                        .jsonPrimitive.content,
                )
                page.close()
            }
        }
    }

    @Test
    fun `idle pages expire while recently used pages retain authority`() {
        var time = 0L
        AppSharingAssets { time }.use { server ->
            val idle = server.open(buildJsonObject {}, false) { buildJsonObject {} }
            val live = server.open(buildJsonObject {}, false) { buildJsonObject {} }
            time = TimeUnit.SECONDS.toNanos(90)
            assertEquals(200, get(URI(live.url)).statusCode())
            time = TimeUnit.SECONDS.toNanos(121)
            assertEquals(404, get(URI(idle.url)).statusCode())
            assertEquals(200, get(URI(live.url)).statusCode())
            time = TimeUnit.SECONDS.toNanos(242)
            assertEquals(404, get(URI(live.url)).statusCode())
        }
    }

    @Test
    fun `viewer close RPC retires capability and frees capacity beyond sixteen lifetime opens`() {
        AppSharingAssets().use { server ->
            repeat(20) {
                val page = server.open(buildJsonObject {}, false) { buildJsonObject { put("closed", true) } }
                val uri = URI(page.url)
                val origin = "${uri.scheme}://${uri.authority}"
                val token = uri.path.split('/')[1]
                assertEquals(200, post(uri.resolve("rpc"), origin, token, "mediaClose").statusCode())
                assertEquals(404, post(uri.resolve("rpc"), origin, token).statusCode())
                assertEquals(404, get(uri).statusCode())
            }
            repeat(16) { server.open(buildJsonObject {}, false) { buildJsonObject {} } }
            assertFailsWith<IllegalStateException> { server.open(buildJsonObject {}, false) { buildJsonObject {} } }
        }
    }

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    private fun get(uri: URI): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(
        uri: URI,
        origin: String?,
        token: String?,
        action: String = "peerHeartbeat",
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(
                    uri,
                ).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
        if (origin != null) request.header("Origin", origin)
        if (token != null) request.header("X-Boss-App-Token", token)
        val payload = buildJsonObject { put("action", action) }.toString()
        return client.send(
            request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }
}
