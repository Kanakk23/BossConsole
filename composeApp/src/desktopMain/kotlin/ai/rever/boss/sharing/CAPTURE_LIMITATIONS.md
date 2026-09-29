# Exact application capture

The macOS 14+ adapter captures the selected Compose window and up to seven visible owned Compose windows/dialogs. Each source is bound to a live NSWindow number and the current process ID, then captured with its own desktop-independent ScreenCaptureKit filter. The host composites only these verified sources. It never captures a display, chooses by title, or selects the first available window. Background and occluded window capture is supported. Geometry, ownership, native handle, modality, and surface membership are fenced before input; resize/dialog changes discard old frames and advance geometry revision.

Capture starts and expansions are available only through the native macOS Window menu, with no keyboard shortcut or remotely clickable content action. Settings can select windows but cannot activate capture. Scoped remote input targets captured content components and cannot invoke the native menu. Any future start entry point must preserve this local-consent boundary.

Unknown owned native windows fail closed. Native menus, arbitrary system sheets, IME, and non-Compose native popups still require native identity and input adapters; they are not silently substituted with display pixels. All content inside selected application windows is visible to admitted viewers, including sensitive tabs. There is no automatic secret redaction.

One capture loop serves one publication, regardless of viewer count. PNG snapshots run at most ten times per second with one native request in flight; no screenshots are requested when there is no viewer demand. A late frame is discarded if its layout, demand, or publication changed. Session deactivation (including platform lock notifications), display sleep, and system sleep stop the publication through documented JDK Desktop listeners; missing platform event support disables capture. Stopped shares never resume automatically after unlock. The actual OS lock notification still needs a platform-specific hardware validation run; tests inject the same stop event without locking the user's machine.

Input goes only to descendants of snapshot windows. BrowserView targets use JxBrowser dispatch; AWT keys explicitly bypass global focus retargeting. No Robot or OS-wide key/pointer injection is used. Parent controls cannot bypass a captured modal child. Production supports scoped background input without bringing the host window to the foreground. Synthetic non-focusable Compose and Swing windows verify text entry and button activation without OS focus; input cannot reach an unrelated active application. Embedded browser focus and accelerated content still require the real-application fidelity check. The host installs a backend-verified controller lease, and Kotlin independently checks peer, session, generation, window, geometry revision, expiry, and sequence. Releases occur on revoke, expiry, geometry replacement, window removal, and target unavailability.

Opt-in native tests create only synthetic test windows. They check selected-window pixels under an unrelated occluder, Retina dimensions, owned-dialog composition, stale geometry, actual background Compose/Swing text/button delivery without OS focus, session-loss teardown, and demand gating. These tests do not certify mixed accelerated browser/terminal/editor fidelity, IME, or every native menu/system dialog. Tests use display color-management tolerance rather than claiming byte-exact RGB values.

## Other desktop platforms

Windows and Linux remain explicitly unavailable in the production capability gate. Implementing a facade around an untested screenshot path would not make them supported. The following are the exact native boundaries required for those adapters:

- Windows 10 1903+: use `IGraphicsCaptureItemInterop::CreateForWindow(HWND)` with the actual Compose HWND, verify `GetWindowThreadProcessId`, consume a free-threaded Direct3D11 frame pool, and retire on `GraphicsCaptureItem.Closed`. A native WinRT/D3D bridge and Windows build/signing/runtime tests are required. `PrintWindow` delegates to the target's `WM_PRINT` handler and is not a GPU-fidelity fallback; desktop BitBlt and title lookup are prohibited. Session stop must bind WTS session-lock/disconnect events, with initial session-state checks.
- X11: use `XCompositeNameWindowPixmap` on the exact Compose XID and read the named pixmap, with XRes owner-PID validation and native lifecycle/error handling. Reading an ordinary obscured window via `XGetImage` has undefined contents without backing storage. Never capture the root drawable or substitute a display. A native helper is preferable to changing the JVM-wide Xlib error handler. Composite, XRes, DPI mapping, and accelerated-surface validation are prerequisites.
- Wayland: the standard ScreenCast portal returns a selected PipeWire stream and source type, not proof that it is the caller's supplied Compose window. Its parent-window identifier only positions the consent dialog. An exact source-identity mechanism or a separately authorized picker flow is required; source names and first-stream heuristics are not acceptable.

Primary API references:

- [Apple: desktop-independent window filter](https://developer.apple.com/documentation/screencapturekit/sccontentfilter?language=objc)
- [JDK: user session lock reasons](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/java/awt/desktop/UserSessionEvent.Reason.html)
- [JDK: supported Desktop event actions](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/java/awt/Desktop.Action.html)
- [Microsoft: exact HWND capture item](https://learn.microsoft.com/en-us/windows/win32/api/windows.graphics.capture.interop/nf-windows-graphics-capture-interop-igraphicscaptureiteminterop-createforwindow)
- [Microsoft: Graphics Capture frame pools](https://learn.microsoft.com/en-us/windows/uwp/audio-video-camera/screen-capture)
- [Microsoft: PrintWindow semantics](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-printwindow)
- [X.Org: Composite off-screen window storage](https://www.x.org/guide/extensions/)
- [X.Org: XGetImage semantics](https://www.x.org/releases/X11R7.5/doc/libX11/libX11.html)
- [XDG: ScreenCast portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.ScreenCast.html)
- [XDG: parent-window identifiers](https://flatpak.github.io/xdg-desktop-portal/docs/window-identifiers.html)
