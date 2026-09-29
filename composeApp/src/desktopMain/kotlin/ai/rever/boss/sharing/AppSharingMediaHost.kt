package ai.rever.boss.sharing

import ai.rever.boss.plugin.browser.BoundedBrowserCall
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.plugin.browser.installBrowserChromeOrClose
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.js.JsAccessible
import com.teamdev.jxbrowser.js.JsObject
import com.teamdev.jxbrowser.navigation.event.LoadFinished
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A single publisher page per shared window. Frame submission retains only the latest image. */
internal class AppSharingMediaHost(
    private val page: AppViewerPage,
    private val onState: (JsonObject) -> Unit,
    private val onInput: (JsonObject) -> Unit,
) : AutoCloseable {
    private val calls = BoundedBrowserCall("boss-app-share-media")
    private val closed = AtomicBoolean()
    private val initialized = AtomicBoolean()
    private val pumping = AtomicBoolean()
    private val latest = AtomicReference<AppCapturedFrame?>()

    @Volatile private var browser: Browser? = null
    private val bridge =
        AppSharingMediaBridge(
            { value -> if (!closed.get()) onState(value) },
            { value -> if (!closed.get()) onInput(value) },
        )

    fun start() {
        calls.post {
            if (closed.get()) return@post
            try {
                val next = FluckEngine.engine.newBrowser().also { installBrowserChromeOrClose(it) }
                browser = next
                next.navigation().on(LoadFinished::class.java) {
                    calls.post {
                        if (closed.get() || !initialized.compareAndSet(false, true)) return@post
                        try {
                            check(next.url() == page.url) { "Unexpected media page navigation" }
                            val frame = next.mainFrame().orElseThrow()
                            frame.executeJavaScript<JsObject>("window")?.putProperty("__bossAppShareBridge", bridge)
                            frame.executeJavaScript<Any?>("window.BossAppShareHost.start(window.__bossAppShareConfig);")
                        } catch (_: Exception) {
                            onState(
                                buildJsonObject {
                                    put("state", "error")
                                    put("message", "Media initialization failed")
                                },
                            )
                        }
                    }
                }
                next.navigation().loadUrl(page.url)
            } catch (_: Exception) {
                onState(
                    buildJsonObject {
                        put("state", "error")
                        put("message", "Browser media runtime unavailable")
                    },
                )
            }
        }
    }

    fun frame(frame: AppCapturedFrame) {
        if (closed.get()) return
        latest.set(frame)
        pump()
    }

    private fun pump() {
        if (!initialized.get() || !pumping.compareAndSet(false, true)) return
        calls.post {
            try {
                while (!closed.get()) {
                    val captured = latest.getAndSet(null) ?: break
                    val payload =
                        buildJsonObject {
                            put("png", Base64.getEncoder().encodeToString(captured.png))
                            put("width", captured.width)
                            put("height", captured.height)
                            put("geometryRevision", captured.geometryRevision)
                        }
                    browser?.takeUnless { it.isClosed }?.mainFrame()?.orElse(null)?.executeJavaScript<Any?>(
                        "window.BossAppShareHost.frame($payload);",
                    )
                }
            } catch (_: Exception) {
                onState(
                    buildJsonObject {
                        put("state", "error")
                        put("message", "Media frame delivery failed")
                    },
                )
            } finally {
                pumping.set(false)
                if (latest.get() != null && !closed.get()) pump()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        latest.set(null)
        page.close()
        calls.post {
            runCatching {
                browser?.mainFrame()?.orElse(null)?.executeJavaScript<Any?>("window.BossAppShareHost?.stop();")
            }
            runCatching { browser?.close() }
            browser = null
        }
        calls.shutdown()
    }
}

internal class AppSharingMediaBridge(
    private val receiveState: (JsonObject) -> Unit,
    private val receiveInput: (JsonObject) -> Unit,
) {
    @JsAccessible
    fun state(json: String) {
        if (json.length > 8192) return
        runCatching { receiveState(Json.parseToJsonElement(json).jsonObject) }
    }

    @JsAccessible
    fun input(json: String) {
        if (json.length > 8192) return
        runCatching { receiveInput(Json.parseToJsonElement(json).jsonObject) }
    }
}
