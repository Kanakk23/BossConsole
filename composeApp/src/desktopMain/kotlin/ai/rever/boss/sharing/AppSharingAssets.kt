package ai.rever.boss.sharing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class AppViewerPage(
    val url: String,
    val close: () -> Unit,
)

/** Private loopback pages proxy only one admitted peer, never a general host-token API. */
internal class AppSharingAssets(
    private val now: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class Page(
        val config: JsonObject,
        val host: Boolean,
        val request: suspend (JsonObject) -> JsonObject,
        val lastAccess: AtomicLong,
    )

    private val pages = ConcurrentHashMap<String, Page>()
    private var closed = false
    private val executor =
        Executors.newFixedThreadPool(4) {
            Thread(it, "boss-app-share-assets").apply { isDaemon = true }
        }
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16).also {
            it.executor = executor
            it.createContext("/", ::handle)
            it.start()
        }
    private val origin get() = "http://127.0.0.1:${server.address.port}"
    private val reaper =
        Executors
            .newSingleThreadScheduledExecutor {
                Thread(it, "boss-app-share-expiry").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay(::evictIdlePages, 30, 30, TimeUnit.SECONDS) }

    private fun evictIdlePages() {
        val cutoff = now() - TimeUnit.MINUTES.toNanos(2)
        pages.entries.removeIf { it.value.lastAccess.get() < cutoff }
    }

    @Synchronized
    fun open(
        config: JsonObject,
        host: Boolean,
        request: suspend (JsonObject) -> JsonObject,
    ): AppViewerPage {
        check(!closed) { "Viewer server is closed" }
        evictIdlePages()
        check(pages.values.count { it.host == host } < 16) { "Too many sharing windows or viewers" }
        val token = randomSecret()
        val root = "$origin/$token/"
        pages[token] =
            Page(
                buildJsonObject {
                    config.forEach { (key, value) -> put(key, value) }
                    put("rpcUrl", root + "rpc")
                    put("rpcToken", token)
                },
                host,
                request,
                AtomicLong(now()),
            )
        return AppViewerPage(root + if (host) "host.html" else "viewer.html") { pages.remove(token) }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (error: AssetHttpException) {
            respond(exchange, error.status, "application/json", "{\"error\":\"unauthorized\"}".toByteArray())
        } catch (error: AppSharingException) {
            val reason = error.reason.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "request_failed"
            val code =
                when (reason) {
                    "unauthorized", "sign_in_required", "account_changed" -> 401
                    "approval_required" -> 403
                    "conflict", "publication_pending" -> 409
                    else -> 503
                }
            runCatching { respond(exchange, code, "application/json", "{\"error\":\"$reason\"}".toByteArray()) }
        } catch (_: Exception) {
            runCatching { respond(exchange, 400, "application/json", "{\"error\":\"request_failed\"}".toByteArray()) }
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange) {
        evictIdlePages()
        checkRequest(exchange.requestHeaders.getFirst("Host") == "127.0.0.1:${server.address.port}", 403)
        val parts =
            exchange.requestURI.rawPath
                .removePrefix("/")
                .split('/')
        checkRequest(parts.size == 2, 404)
        val token = parts[0]
        val page = pages[token] ?: throw AssetHttpException(404)
        page.lastAccess.set(now())
        if (parts[1] == "rpc") serveRpc(exchange, token, page) else serveAsset(exchange, token, page, parts[1])
    }

    private fun serveRpc(
        exchange: HttpExchange,
        token: String,
        page: Page,
    ) {
        val supplied = exchange.requestHeaders.getFirst("X-Boss-App-Token") ?: ""
        val sameOrigin = exchange.requestHeaders.getFirst("Origin") == origin
        val validToken = MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())
        if (exchange.requestMethod != "POST" || !sameOrigin || !validToken) throw AssetHttpException(403)
        val bytes = exchange.requestBody.use { it.readNBytes(1024 * 1024 + 1) }
        require(bytes.size <= 1024 * 1024)
        val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val response = runBlocking { page.request(request) }
        if (!page.host && request["action"]?.jsonPrimitive?.contentOrNull == "mediaClose") pages.remove(token, page)
        respond(exchange, 200, "application/json", response.toString().toByteArray())
    }

    private fun serveAsset(
        exchange: HttpExchange,
        token: String,
        page: Page,
        name: String,
    ) {
        val validName = name.matches(Regex("[a-z][a-z0-9-]*\\.(html|mjs|js|css)"))
        val expectedHtml = if (page.host) "host.html" else "viewer.html"
        checkRequest(exchange.requestMethod == "GET" && validName, 404)
        checkRequest(!name.endsWith(".html") || name == expectedHtml, 404)
        var bytes =
            javaClass.getResourceAsStream("/app-sharing/$name")?.use { it.readBytes() }
                ?: throw AssetHttpException(404)
        if (name.endsWith(".html")) {
            val config =
                page.config
                    .toString()
                    .replace("<", "\\u003c")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029")
            val bootstrap = "<head><script nonce=\"$token\">window.__bossAppShareConfig=$config;</script>"
            bytes = bytes.decodeToString().replace("<head>", bootstrap).toByteArray()
        }
        val type =
            when (name.substringAfterLast('.')) {
                "html" -> "text/html; charset=utf-8"
                "css" -> "text/css"
                else -> "text/javascript"
            }
        exchange.responseHeaders.set(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self' 'nonce-$token'; style-src 'self' 'unsafe-inline'; " +
                "connect-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; worker-src 'self' blob:; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
        respond(exchange, 200, type, bytes)
    }

    private fun checkRequest(
        valid: Boolean,
        status: Int,
    ) {
        if (!valid) throw AssetHttpException(status)
    }

    private class AssetHttpException(
        val status: Int,
    ) : IllegalArgumentException()

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        type: String,
        bytes: ByteArray,
    ) {
        exchange.responseHeaders.set("Content-Type", type)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pages.clear()
        reaper.shutdownNow()
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun randomSecret(): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    }
}
