package ai.rever.boss.sharing

import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.ui.KeyModifiers
import com.teamdev.jxbrowser.ui.MouseButton
import com.teamdev.jxbrowser.ui.Point
import com.teamdev.jxbrowser.ui.ScrollType
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyReleased
import com.teamdev.jxbrowser.ui.event.KeyTyped
import com.teamdev.jxbrowser.ui.event.MouseDragged
import com.teamdev.jxbrowser.ui.event.MouseMoved
import com.teamdev.jxbrowser.ui.event.MousePressed
import com.teamdev.jxbrowser.ui.event.MouseReleased
import com.teamdev.jxbrowser.ui.event.MouseWheel
import com.teamdev.jxbrowser.view.swing.BrowserView
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities

/** Routes only to descendants of the consented window. Never synthesizes global OS input. */
// Separate event translations keep AWT and browser authority checks explicit.
@Suppress("TooManyFunctions")
internal class AwtAppInputSink(
    private val window: Window,
    private val privateSurfaceAllowed: () -> Boolean = { true },
    private val requireForeground: Boolean = true,
) : AppScopedInputSink {
    private data class HeldPointer(
        val component: Component,
        val x: Int,
        val y: Int,
    )

    private val buttons = mutableMapOf<Int, HeldPointer>()
    private val keys = mutableMapOf<String, Pair<Component, AppInputEvent.Key>>()

    private var surfaceSnapshot: AppSurfaceSnapshot? = null
    private var keyboardTarget: Component? = null

    fun updateSurfaces(snapshot: AppSurfaceSnapshot) =
        onEdt {
            require(snapshot.surfaces.firstOrNull()?.window === window)
            require(snapshot.surfaces.all { isOwnedBy(it.window, window) })
            if (surfaceSnapshot != snapshot) releaseAll()
            surfaceSnapshot = snapshot
        }

    override fun isAvailable(): Boolean {
        if (!window.isDisplayable || !window.isShowing || !privateSurfaceAllowed()) return false
        val snapshot = surfaceSnapshot
        val snapshotCurrent = snapshot == null || runCatching { captureSurfaceSnapshot(window) }.getOrNull() == snapshot
        val owned = snapshot?.surfaces?.map { it.window } ?: listOf(window)
        return snapshotCurrent && (!requireForeground || owned.any { it.isFocused }) &&
            (snapshot != null || window.ownedWindows.none { it.isShowing })
    }

    private fun allowedWindow(candidate: Window?): Boolean =
        candidate != null &&
            (surfaceSnapshot?.surfaces?.any { it.window === candidate } ?: (candidate === window))

    private fun focusedComponent(): Component? =
        surfaceSnapshot
            ?.surfaces
            ?.lastOrNull { it.window.isFocused }
            ?.window
            ?.focusOwner
            ?: window.focusOwner ?: keyboardTarget

    override fun apply(event: AppInputEvent): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (!isAvailable()) {
            releaseAll()
            return false
        }
        return when (event) {
            is AppInputEvent.Pointer -> applyPointer(event)
            is AppInputEvent.Wheel -> applyWheel(event)
            is AppInputEvent.Key -> applyKey(event)
        }
    }

    // Explicit early rejection keeps held-target and duplicate-press checks auditable.
    @Suppress("ReturnCount")
    private fun applyPointer(event: AppInputEvent.Pointer): Boolean {
        val hit = hit(event.x, event.y) ?: return false
        val target =
            if (event.action == "up") {
                buttons.remove(event.button) ?: return false
            } else {
                buttons.values.firstOrNull() ?: hit
            }
        val position = SwingUtilities.convertPoint(hit.component, hit.x, hit.y, target.component)
        if (event.action == "down") {
            if (buttons.containsKey(event.button)) return false
            target.component.requestFocusInWindow()
            keyboardTarget = target.component
            buttons[event.button] = HeldPointer(target.component, position.x, position.y)
        }
        buttons.replaceAll { _, held ->
            if (held.component === target.component) held.copy(x = position.x, y = position.y) else held
        }
        pointer(
            target.component,
            event.action,
            if (event.action ==
                "move"
            ) {
                buttons.keys.firstOrNull() ?: event.button
            } else {
                event.button
            },
            position.x,
            position.y,
        )
        return true
    }

    private fun applyWheel(event: AppInputEvent.Wheel): Boolean {
        val hit = hit(event.x, event.y) ?: return false
        val browser = hit.component as? BrowserView
        if (browser != null) {
            browser.browser.dispatch(
                MouseWheel
                    .newBuilder(Point.of(hit.x, hit.y))
                    .keyModifiers(
                        browserModifiers(),
                    ).deltaX(event.deltaX.toFloat())
                    .deltaY(event.deltaY.toFloat())
                    .scrollType(ScrollType.UNIT_SCROLL)
                    .build(),
            )
        } else {
            hit.component.dispatchEvent(
                MouseWheelEvent(
                    hit.component,
                    MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    modifiers(),
                    hit.x,
                    hit.y,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    event.deltaY.toInt().coerceIn(-1000, 1000),
                ),
            )
        }
        return true
    }

    // Releases must retain their original component instead of using the current focus owner.
    @Suppress("ReturnCount")
    private fun applyKey(event: AppInputEvent.Key): Boolean {
        val target =
            if (event.action == "up") {
                keys.remove(event.code)?.first ?: return false
            } else {
                keys[event.code]?.first ?: scoped(focusedComponent()) ?: return false
            }
        if (event.action == "down") keys[event.code] = target to event
        key(target, event)
        return true
    }

    override fun releaseAll() {
        check(SwingUtilities.isEventDispatchThread())
        val heldButtons = buttons.toMap()
        val heldKeys = keys.toMap()
        buttons.clear()
        keys.clear()
        keyboardTarget = null
        heldButtons.forEach { (button, target) ->
            runCatching { pointer(target.component, "up", button, target.x, target.y) }
        }
        heldKeys.values.forEach { (target, event) ->
            runCatching {
                key(
                    target,
                    event.copy(action = "up", alt = false, ctrl = false, meta = false, shift = false),
                )
            }
        }
    }

    // Reject stale/foreign/modal-parent coordinates before dispatch; keep the guards explicit.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun hit(
        x: Double,
        y: Double,
    ): HeldPointer? {
        val snapshot = surfaceSnapshot
        val px = (x * ((snapshot?.logicalWidth ?: window.width) - 1)).toInt() + (snapshot?.x ?: window.x)
        val py = (y * ((snapshot?.logicalHeight ?: window.height) - 1)).toInt() + (snapshot?.y ?: window.y)
        val targetWindow =
            snapshot
                ?.surfaces
                ?.asReversed()
                ?.firstOrNull {
                    val g = it.geometry
                    px in g.x until g.x + g.logicalWidth && py in g.y until g.y + g.logicalHeight
                }?.window ?: window.takeIf { snapshot == null } ?: return null
        val modal = snapshot?.surfaces?.lastOrNull { it.modal }?.window
        if (modal != null && !isOwnedBy(targetWindow, modal)) return null
        val localX = px - targetWindow.x
        val localY = py - targetWindow.y
        val insets = targetWindow.insets
        val inHorizontalBounds = localX >= insets.left && localX < targetWindow.width - insets.right
        val inVerticalBounds = localY >= insets.top && localY < targetWindow.height - insets.bottom
        if (!inHorizontalBounds || !inVerticalBounds) return null
        return SwingUtilities.getDeepestComponentAt(targetWindow, localX, localY)?.let(::scoped)?.let { target ->
            val local = SwingUtilities.convertPoint(targetWindow, localX, localY, target)
            HeldPointer(target, local.x, local.y)
        }
    }

    private fun scoped(component: Component?): Component? {
        if (component == null || component === window ||
            !allowedWindow(SwingUtilities.getWindowAncestor(component))
        ) {
            return null
        }
        return generateSequence(component) { it.parent }
            .takeWhile { it !== window }
            .filterIsInstance<BrowserView>()
            .firstOrNull() ?: component
    }

    // Parallel AWT/JxBrowser translations deliberately retain every explicit button/action branch.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun pointer(
        target: Component,
        action: String,
        button: Int,
        x: Int,
        y: Int,
    ) {
        // Detached/replaced components must not acquire authority in another window.
        if (!allowedWindow(SwingUtilities.getWindowAncestor(target))) return
        if (target is BrowserView) {
            val point = Point.of(x, y)
            val browserButton =
                when (button) {
                    1 -> MouseButton.MIDDLE
                    2 -> MouseButton.SECONDARY
                    else -> MouseButton.PRIMARY
                }
            when (action) {
                "down" -> {
                    target.browser.dispatch(
                        MousePressed
                            .newBuilder(point)
                            .button(browserButton)
                            .keyModifiers(browserModifiers())
                            .clickCount(1)
                            .build(),
                    )
                }

                "up" -> {
                    target.browser.dispatch(
                        MouseReleased
                            .newBuilder(point)
                            .button(browserButton)
                            .keyModifiers(browserModifiers())
                            .clickCount(1)
                            .build(),
                    )
                }

                else -> {
                    if (buttons.isEmpty()) {
                        target.browser.dispatch(MouseMoved.newBuilder(point).keyModifiers(browserModifiers()).build())
                    } else {
                        target.browser.dispatch(
                            MouseDragged
                                .newBuilder(point)
                                .button(browserButton)
                                .keyModifiers(browserModifiers())
                                .build(),
                        )
                    }
                }
            }
        } else {
            val awtButton =
                when (button) {
                    1 -> MouseEvent.BUTTON2
                    2 -> MouseEvent.BUTTON3
                    else -> MouseEvent.BUTTON1
                }
            val id =
                when (action) {
                    "down" -> MouseEvent.MOUSE_PRESSED
                    "up" -> MouseEvent.MOUSE_RELEASED
                    else -> if (buttons.isEmpty()) MouseEvent.MOUSE_MOVED else MouseEvent.MOUSE_DRAGGED
                }
            target.dispatchEvent(
                MouseEvent(
                    target,
                    id,
                    System.currentTimeMillis(),
                    modifiers(),
                    x,
                    y,
                    if (action == "move") 0 else 1,
                    false,
                    if (action == "move") MouseEvent.NOBUTTON else awtButton,
                ),
            )
        }
    }

    // Key release and printable typing differ across AWT and JxBrowser; preserve explicit guards.
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private fun key(
        target: Component,
        event: AppInputEvent.Key,
    ) {
        if (!allowedWindow(SwingUtilities.getWindowAncestor(target))) return
        val code = awtKeyCode(event.code) ?: return
        val char = event.key.singleOrNull()?.takeUnless { it.isISOControl() } ?: KeyEvent.CHAR_UNDEFINED
        val flags =
            (if (event.alt) InputEvent.ALT_DOWN_MASK else 0) or (if (event.ctrl) InputEvent.CTRL_DOWN_MASK else 0) or
                (if (event.meta) InputEvent.META_DOWN_MASK else 0) or
                (if (event.shift) InputEvent.SHIFT_DOWN_MASK else 0)
        val printable = char != KeyEvent.CHAR_UNDEFINED && !event.ctrl && !event.meta && !event.alt
        if (target is BrowserView) {
            val browserCode = browserKeyCode(event.code) ?: return
            val mods =
                KeyModifiers
                    .newBuilder()
                    .altDown(
                        event.alt,
                    ).controlDown(event.ctrl)
                    .metaDown(event.meta)
                    .shiftDown(event.shift)
                    .build()
            if (event.action == "down") {
                target.browser.dispatch(
                    KeyPressed
                        .newBuilder(browserCode)
                        .keyChar(char)
                        .keyModifiers(mods)
                        .build(),
                )
                if (printable) {
                    target.browser.dispatch(
                        KeyTyped
                            .newBuilder(browserCode)
                            .keyChar(char)
                            .keyModifiers(mods)
                            .build(),
                    )
                }
            } else {
                target.browser.dispatch(KeyReleased.newBuilder(browserCode).keyModifiers(mods).build())
            }
        } else {
            // dispatchEvent(KeyEvent) normally re-enters global focus retargeting. Explicit
            // redispatch bypasses that path, so cleanup releases cannot reach a newly focused app window.
            val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            focusManager.redispatchEvent(
                target,
                KeyEvent(
                    target,
                    if (event.action ==
                        "down"
                    ) {
                        KeyEvent.KEY_PRESSED
                    } else {
                        KeyEvent.KEY_RELEASED
                    },
                    System.currentTimeMillis(),
                    flags,
                    code,
                    char,
                ),
            )
            if (event.action == "down" && printable) {
                focusManager.redispatchEvent(
                    target,
                    KeyEvent(
                        target,
                        KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(),
                        flags,
                        KeyEvent.VK_UNDEFINED,
                        char,
                    ),
                )
            }
        }
    }

    private fun browserModifiers(): KeyModifiers =
        KeyModifiers
            .newBuilder()
            .shiftDown(keys.keys.any { it.startsWith("Shift") })
            .controlDown(keys.keys.any { it.startsWith("Control") })
            .altDown(keys.keys.any { it.startsWith("Alt") })
            .metaDown(keys.keys.any { it.startsWith("Meta") })
            .build()

    private fun modifiers(): Int =
        keys.keys.fold(0) { mask, code ->
            mask or
                when {
                    code.startsWith("Shift") -> InputEvent.SHIFT_DOWN_MASK
                    code.startsWith("Control") -> InputEvent.CTRL_DOWN_MASK
                    code.startsWith("Alt") -> InputEvent.ALT_DOWN_MASK
                    code.startsWith("Meta") -> InputEvent.META_DOWN_MASK
                    else -> 0
                }
        } or
            buttons.keys.fold(0) { mask, button ->
                mask or
                    when (button) {
                        1 -> InputEvent.BUTTON2_DOWN_MASK
                        2 -> InputEvent.BUTTON3_DOWN_MASK
                        else -> InputEvent.BUTTON1_DOWN_MASK
                    }
            }
}

private fun awtKeyCode(code: String): Int? =
    when {
        code.startsWith("Key") && code.length == 4 -> {
            KeyEvent.getExtendedKeyCodeForChar(code[3].code)
        }

        code.startsWith("Digit") && code.length == 6 -> {
            KeyEvent.getExtendedKeyCodeForChar(code[5].code)
        }

        else -> {
            mapOf(
                "Enter" to KeyEvent.VK_ENTER,
                "Escape" to KeyEvent.VK_ESCAPE,
                "Backspace" to KeyEvent.VK_BACK_SPACE,
                "Tab" to KeyEvent.VK_TAB,
                "Space" to KeyEvent.VK_SPACE,
                "ArrowLeft" to KeyEvent.VK_LEFT,
                "ArrowRight" to KeyEvent.VK_RIGHT,
                "ArrowUp" to KeyEvent.VK_UP,
                "ArrowDown" to KeyEvent.VK_DOWN,
                "Delete" to KeyEvent.VK_DELETE,
                "Home" to KeyEvent.VK_HOME,
                "End" to KeyEvent.VK_END,
                "PageUp" to KeyEvent.VK_PAGE_UP,
                "PageDown" to KeyEvent.VK_PAGE_DOWN,
                "ShiftLeft" to KeyEvent.VK_SHIFT,
                "ShiftRight" to KeyEvent.VK_SHIFT,
                "ControlLeft" to KeyEvent.VK_CONTROL,
                "ControlRight" to KeyEvent.VK_CONTROL,
                "AltLeft" to KeyEvent.VK_ALT,
                "AltRight" to KeyEvent.VK_ALT,
                "MetaLeft" to KeyEvent.VK_META,
                "MetaRight" to KeyEvent.VK_META,
                "Minus" to KeyEvent.VK_MINUS,
                "Equal" to KeyEvent.VK_EQUALS,
                "BracketLeft" to KeyEvent.VK_OPEN_BRACKET,
                "BracketRight" to KeyEvent.VK_CLOSE_BRACKET,
                "Backslash" to KeyEvent.VK_BACK_SLASH,
                "Semicolon" to KeyEvent.VK_SEMICOLON,
                "Quote" to KeyEvent.VK_QUOTE,
                "Comma" to KeyEvent.VK_COMMA,
                "Period" to KeyEvent.VK_PERIOD,
                "Slash" to KeyEvent.VK_SLASH,
                "Backquote" to KeyEvent.VK_BACK_QUOTE,
            )[code]
        }
    }

private fun browserKeyCode(code: String): KeyCode? {
    val suffix =
        when {
            code.startsWith("Key") && code.length == 4 -> {
                code.substring(3)
            }

            code.startsWith("Digit") && code.length == 6 -> {
                code.substring(5)
            }

            else -> {
                mapOf(
                    "Enter" to "RETURN",
                    "Escape" to "ESCAPE",
                    "Backspace" to "BACK",
                    "Tab" to "TAB",
                    "Space" to "SPACE",
                    "ArrowLeft" to "LEFT",
                    "ArrowRight" to "RIGHT",
                    "ArrowUp" to "UP",
                    "ArrowDown" to "DOWN",
                    "Delete" to "DELETE",
                    "Home" to "HOME",
                    "End" to "END",
                    "PageUp" to "PRIOR",
                    "PageDown" to "NEXT",
                    "ShiftLeft" to "SHIFT",
                    "ShiftRight" to "SHIFT",
                    "ControlLeft" to "CONTROL",
                    "ControlRight" to "CONTROL",
                    "AltLeft" to "MENU",
                    "AltRight" to "MENU",
                    "MetaLeft" to "LWIN",
                    "MetaRight" to "RWIN",
                    "Minus" to "OEM_MINUS",
                    "Equal" to "OEM_PLUS",
                    "BracketLeft" to "OEM_4",
                    "BracketRight" to "OEM_6",
                    "Backslash" to "OEM_5",
                    "Semicolon" to "OEM_1",
                    "Quote" to "OEM_7",
                    "Comma" to "OEM_COMMA",
                    "Period" to "OEM_PERIOD",
                    "Slash" to "OEM_2",
                    "Backquote" to "OEM_3",
                )[code]
            }
        } ?: return null
    return runCatching { KeyCode.valueOf("KEY_CODE_$suffix") }.getOrNull()
}

private fun isOwnedBy(
    candidate: Window,
    owner: Window,
): Boolean = generateSequence(candidate) { it.owner }.any { it === owner }
