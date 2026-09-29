package ai.rever.boss.sharing

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Window
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Local, explicit consent binds a share identity to this exact live window object. */
internal data class AppCaptureTarget(
    val windowId: String,
    val generation: String,
    val awtWindow: Window,
)

internal data class AppCapturedFrame(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val geometryRevision: Long,
    val surfaces: AppSurfaceSnapshot? = null,
)

internal data class AppCaptureCapability(
    val supported: Boolean,
    val reason: String? = null,
    val backgroundWindows: Boolean = false,
    val ownedDialogs: Boolean = false,
)

internal interface AppWindowCapture {
    fun capability(target: AppCaptureTarget): AppCaptureCapability

    fun start(
        target: AppCaptureTarget,
        onFrame: (AppCapturedFrame) -> Unit,
        onStopped: (String) -> Unit,
        shouldCapture: () -> Boolean = { true },
    ): AutoCloseable
}

/**
 * Exact ScreenCaptureKit capture of the selected window and verified Compose-owned dialogs.
 * Background/occluded windows are supported. At most one native request runs at a time;
 * there is no display/title fallback. Unknown native surfaces and unsupported platforms fail closed.
 */
internal class ExactAppWindowCapture(
    private val backend: ExactWindowFrameSource = MacAppWindowFrameSource(),
    private val isMac: Boolean = SystemUtils.isMacOS,
    private val sessionMonitor: AppCaptureSessionMonitor = DesktopAppCaptureSessionMonitor(),
    private val failureObserver: (Throwable) -> Unit = {},
) : AppWindowCapture {
    override fun capability(target: AppCaptureTarget): AppCaptureCapability =
        when {
            !isMac -> {
                AppCaptureCapability(
                    false,
                    "Exact application-window capture is currently available only on macOS 14 or newer.",
                )
            }

            target.awtWindow !is ComposeWindow -> {
                AppCaptureCapability(false, "This window has no supported native identity.")
            }

            !backend.available() -> {
                AppCaptureCapability(
                    false,
                    "ScreenCaptureKit capture is unavailable or Screen Recording permission is missing.",
                )
            }

            !sessionMonitor.supported() -> {
                AppCaptureCapability(false, "OS session lock and sleep monitoring is unavailable.")
            }

            else -> {
                AppCaptureCapability(true, backgroundWindows = true, ownedDialogs = true)
            }
        }

    // Native/JNA failures, including linkage errors, must terminate publication rather than escape its worker.
    // Keep resource ownership and all terminal transitions in one auditable scope.
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    override fun start(
        target: AppCaptureTarget,
        onFrame: (AppCapturedFrame) -> Unit,
        onStopped: (String) -> Unit,
        shouldCapture: () -> Boolean,
    ): AutoCloseable {
        UUID.fromString(target.windowId)
        UUID.fromString(target.generation)
        check(capability(target).supported) { capability(target).reason ?: "Window capture unsupported" }
        val closed = AtomicBoolean(false)
        val executor =
            Executors.newSingleThreadScheduledExecutor {
                Thread(it, "boss-app-window-capture").apply {
                    isDaemon =
                        true
                }
            }
        val resources = OwnedAppResources()
        resources.own(AutoCloseable { executor.shutdownNow() })
        try {
            resources.own(
                sessionMonitor.watch {
                    if (closed.compareAndSet(false, true)) {
                        resources.close()
                        onStopped("Sharing stopped because the OS session locked, switched, or went to sleep.")
                    }
                },
            )
        } catch (failure: Throwable) {
            resources.close()
            throw failure
        }
        var previous: AppSurfaceSnapshot? = null
        var revision = 0L
        executor.scheduleWithFixedDelay({
            if (closed.get()) return@scheduleWithFixedDelay
            try {
                val geometry = onEdt { captureSurfaceSnapshot(target.awtWindow) }
                if (!shouldCapture()) return@scheduleWithFixedDelay
                if (geometry != previous) {
                    revision++
                    previous = geometry
                }
                val frame = captureSurfaces(geometry, backend)
                check(
                    frame.png.size <= 16 * 1024 * 1024 && frame.width == geometry.width &&
                        frame.height == geometry.height,
                )
                // Closing/replacing a session while native capture is pending cannot emit late pixels.
                val stillExact = onEdt { captureSurfaceSnapshot(target.awtWindow) == geometry }
                if (!stillExact) return@scheduleWithFixedDelay
                if (!closed.get() &&
                    shouldCapture()
                ) {
                    onFrame(AppCapturedFrame(frame.png, frame.width, frame.height, revision, geometry))
                }
            } catch (failure: Throwable) {
                if (closed.get()) return@scheduleWithFixedDelay
                // A dialog closing or a resize during capture invalidates this frame, not the share.
                val changed = runCatching { onEdt { captureSurfaceSnapshot(target.awtWindow) } }.getOrNull()
                if (changed != null && changed != previous && !closed.get()) return@scheduleWithFixedDelay
                runCatching { failureObserver(failure) }
                if (closed.compareAndSet(false, true)) {
                    resources.close()
                    onStopped(
                        "Capture stopped: the window is unavailable, hidden, or capture permission was lost.",
                    )
                }
                // Reporting must never prevent the stop callback, even before app logging is initialized.
                runCatching {
                    BossLogger
                        .forComponent("AppWindowCapture")
                        .warn(LogCategory.BROWSER, "Exact application-window capture failed", error = failure)
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS)
        return AutoCloseable {
            closed.set(true)
            resources.close()
        }
    }
}

internal data class WindowCaptureGeometry(
    val nativeHandle: Long,
    val width: Int,
    val height: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val x: Int,
    val y: Int,
)

internal data class NativeWindowFrame(
    val png: ByteArray,
    val width: Int,
    val height: Int,
)

internal interface ExactWindowFrameSource {
    fun available(): Boolean

    fun capture(
        nativeHandle: Long,
        processId: Long,
        width: Int,
        height: Int,
    ): NativeWindowFrame
}

internal fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
}

/** Exact identity only: duplicate IDs, reused foreign-process IDs and absent windows fail closed. */
internal fun exactCaptureWindowIndex(
    windows: List<Pair<Long, Long>>,
    windowId: Long,
    processId: Long,
): Int? =
    windows
        .withIndex()
        .filter { it.value.first == windowId }
        .singleOrNull()
        ?.takeIf { it.value.second == processId }
        ?.index
