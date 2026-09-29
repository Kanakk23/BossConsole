package ai.rever.boss.sharing

import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Local-only geometry; never serialize Window references or native handles into viewer metadata. */
internal data class AppCapturedSurface(
    val window: Window,
    val geometry: WindowCaptureGeometry,
    val modal: Boolean = window is Dialog && window.isModal,
)

internal data class AppSurfaceSnapshot(
    val surfaces: List<AppCapturedSurface>,
    val x: Int,
    val y: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val width: Int,
    val height: Int,
)

/** AWT ownership is authoritative. Unknown visible owned surfaces must not silently disappear. */
internal fun captureSurfaceSnapshot(root: Window): AppSurfaceSnapshot {
    val surfaces = mutableListOf<AppCapturedSurface>()

    fun visit(window: Window) {
        checkCaptureVisibility(
            window.isDisplayable,
            window.isShowing,
            window is Frame && window.extendedState and Frame.ICONIFIED != 0,
        )
        val handle =
            when (window) {
                is ComposeWindow -> window.windowHandle
                is ComposeDialog -> window.windowHandle
                else -> error("This owned popup has no verified native capture identity")
            }
        check(handle != 0L && surfaces.size < 8) { "Shared surface limit exceeded" }
        val scale = window.graphicsConfiguration.defaultTransform
        surfaces.add(
            AppCapturedSurface(
                window,
                WindowCaptureGeometry(
                    handle,
                    (window.width * scale.scaleX).toInt(),
                    (window.height * scale.scaleY).toInt(),
                    window.width,
                    window.height,
                    window.x,
                    window.y,
                ),
            ),
        )
        window.ownedWindows.filter { it.isShowing }.forEach(::visit)
    }
    visit(root)
    val x = surfaces.minOf { it.geometry.x }
    val y = surfaces.minOf { it.geometry.y }
    val logicalWidth = surfaces.maxOf { it.geometry.x + it.geometry.logicalWidth } - x
    val logicalHeight = surfaces.maxOf { it.geometry.y + it.geometry.logicalHeight } - y
    val scale = root.graphicsConfiguration.defaultTransform
    val width = (logicalWidth * scale.scaleX).toInt()
    val height = (logicalHeight * scale.scaleY).toInt()
    check(width in 1..8192 && height in 1..8192) { "Shared window group is too large" }
    return AppSurfaceSnapshot(surfaces.toList(), x, y, logicalWidth, logicalHeight, width, height)
}

internal fun captureSurfaces(
    snapshot: AppSurfaceSnapshot,
    source: ExactWindowFrameSource,
): NativeWindowFrame {
    val frames =
        snapshot.surfaces.map {
            val geometry = it.geometry
            source.capture(geometry.nativeHandle, ProcessHandle.current().pid(), geometry.width, geometry.height)
        }
    if (frames.size == 1) return frames.single()
    val canvas = BufferedImage(snapshot.width, snapshot.height, BufferedImage.TYPE_INT_RGB)
    val graphics = canvas.createGraphics()
    try {
        snapshot.surfaces.zip(frames).forEach { (surface, frame) ->
            val geometry = surface.geometry
            check(frame.width == geometry.width && frame.height == geometry.height)
            val image = checkNotNull(ImageIO.read(ByteArrayInputStream(frame.png)))
            val scaleX = snapshot.width.toDouble() / snapshot.logicalWidth
            val scaleY = snapshot.height.toDouble() / snapshot.logicalHeight
            graphics.drawImage(
                image,
                ((geometry.x - snapshot.x) * scaleX).toInt(),
                ((geometry.y - snapshot.y) * scaleY).toInt(),
                (geometry.logicalWidth * scaleX).toInt(),
                (geometry.logicalHeight * scaleY).toInt(),
                null,
            )
        }
    } finally {
        graphics.dispose()
    }
    val bytes =
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(canvas, "png", output))
            output.toByteArray()
        }
    check(bytes.size <= 16 * 1024 * 1024)
    return NativeWindowFrame(bytes, snapshot.width, snapshot.height)
}

internal fun checkCaptureVisibility(
    displayable: Boolean,
    showing: Boolean,
    minimized: Boolean,
) {
    check(displayable && showing) { "Shared window closed or hidden" }
    check(!minimized) { "Shared window minimized" }
}
