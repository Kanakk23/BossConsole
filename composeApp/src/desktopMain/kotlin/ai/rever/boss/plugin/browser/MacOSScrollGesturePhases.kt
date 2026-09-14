package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The part of a macOS scroll sequence which comes from fingers still touching the trackpad. */
internal data class ScrollGestureSnapshot(
    val id: Long,
    val active: Boolean,
    val observable: Boolean = active,
    val accumX: Double = 0.0,
    val verticalPath: Double = 0.0,
    val direction: Int = 0,
    val rejected: Boolean = false,
    val reversed: Boolean = false,
    val reachedCommit: Boolean = false,
    val mayBegin: Boolean = false,
    val beganAtEpochMs: Long = 0,
    val claimantIds: Set<Long> = emptySet(),
)

internal data class ScrollGestureEnd(
    val id: Long,
    val cancelled: Boolean,
    val accumX: Double,
    val verticalPath: Double,
    val rejected: Boolean,
    val reversed: Boolean,
    val claimantIds: Set<Long>,
)

/** Claim [claimantId] only while this exact native sequence is still active. */
internal fun claimScrollGesture(
    current: ScrollGestureSnapshot,
    claimantId: Long,
): ScrollGestureSnapshot? =
    when {
        !current.active -> null
        claimantId in current.claimantIds -> current
        else -> current.copy(claimantIds = current.claimantIds + claimantId)
    }

@Suppress("LongParameterList") // Mirrors the independent numeric fields carried by one CGEvent.
internal fun scrollGestureTransition(
    current: ScrollGestureSnapshot,
    phase: Long,
    momentumPhase: Long,
    dx: Double = 0.0,
    dy: Double = 0.0,
    nowEpochMs: Long = 0,
): Pair<ScrollGestureSnapshot, ScrollGestureEnd?> {
    val terminalDx = if (momentumPhase == CG_SCROLL_PHASE_NONE) dx else 0.0
    val terminalDy = if (momentumPhase == CG_SCROLL_PHASE_NONE) dy else 0.0
    return when {
        phase and CG_SCROLL_PHASE_CANCELLED != 0L -> {
            finish(current, terminalDx, terminalDy, cancelled = true)
        }

        phase and CG_SCROLL_PHASE_ENDED != 0L -> {
            finish(current, terminalDx, terminalDy, cancelled = false)
        }

        momentumPhase != CG_SCROLL_PHASE_NONE -> {
            if (current.active) {
                finish(current, 0.0, 0.0, cancelled = true)
            } else {
                current.copy(observable = false) to null
            }
        }

        phase and CG_SCROLL_PHASE_MAY_BEGIN != 0L -> {
            ScrollGestureSnapshot(
                current.id + 1,
                true,
                mayBegin = true,
                beganAtEpochMs = nowEpochMs,
            ) to null
        }

        phase and CG_SCROLL_PHASE_BEGAN != 0L -> {
            accumulate(
                if (current.active && current.mayBegin) {
                    current.copy(mayBegin = false)
                } else {
                    ScrollGestureSnapshot(current.id + 1, true, beganAtEpochMs = nowEpochMs)
                },
                dx,
                dy,
            ) to null
        }

        phase and CG_SCROLL_PHASE_CHANGED != 0L -> {
            (if (current.active) accumulate(current, dx, dy) else current) to null
        }

        else -> {
            current to null
        }
    }
}

private fun finish(
    current: ScrollGestureSnapshot,
    dx: Double,
    dy: Double,
    cancelled: Boolean,
): Pair<ScrollGestureSnapshot, ScrollGestureEnd?> {
    if (!current.active) return current to null
    val final = accumulate(current, dx, dy).copy(active = false, observable = false)
    return final to
        ScrollGestureEnd(
            final.id,
            cancelled,
            final.accumX,
            final.verticalPath,
            final.rejected,
            final.reversed,
            final.claimantIds,
        )
}

@Suppress("CyclomaticComplexMethod") // Each branch is one documented Chrome cancellation tier.
private fun accumulate(
    current: ScrollGestureSnapshot,
    dx: Double,
    dy: Double,
): ScrollGestureSnapshot {
    val x = current.accumX + dx
    val vertical = current.verticalPath + kotlin.math.abs(dy)
    val direction =
        current.direction.takeIf { it != 0 } ?: if (x < 0) {
            -1
        } else if (x > 0) {
            1
        } else {
            0
        }
    val reversed = direction != 0 && x != 0.0 && (if (x < 0) -1 else 1) != direction
    val xDelta = kotlin.math.abs(x)
    val rejected =
        current.rejected || reversed || (
            !current.reachedCommit &&
                (vertical > 2 * xDelta || (vertical * 1.3 > xDelta && vertical > 90 * .125) || vertical > 90 * 3)
        )
    return current.copy(
        active = true,
        observable = true,
        accumX = x,
        verticalPath = vertical,
        direction = direction,
        rejected = rejected,
        reversed = current.reversed || reversed,
        reachedCommit = current.reachedCommit || (!rejected && xDelta >= 90),
    )
}

/**
 * Observes real macOS scroll phases. The event tap is listen-only and is created only after the
 * non-prompting preflight succeeds; Boss never requests Input Monitoring from this path.
 */
// The native tap lifecycle and per-browser claimant registry intentionally share one atomic owner.
@Suppress("TooManyFunctions")
internal object MacOSScrollGesturePhases {
    private val logger = BossLogger.forComponent("MacOSScrollGesturePhases")
    private val listeners = java.util.concurrent.ConcurrentHashMap<Long, (ScrollGestureEnd) -> Unit>()
    private val nextClaimantId = AtomicLong(0)
    private val snapshot = AtomicReference(ScrollGestureSnapshot(0, false))
    private val started = AtomicReference<Boolean?>(null)
    private val retainedCallback = AtomicReference<ScrollEventCallback?>(null)
    private val eventTap = AtomicReference<Pointer?>(null)
    private val runLoop = AtomicReference<Pointer?>(null)
    private val failuresLogged = AtomicLong(0)

    init {
        System.setProperty(PHASE_PROPERTY, "unavailable")
    }

    fun ensureStarted() = startIfAvailable()

    fun register(onEnded: (ScrollGestureEnd) -> Unit): ScrollGestureClaim {
        val claimantId = nextClaimantId.incrementAndGet()
        listeners[claimantId] = onEnded
        startIfAvailable()
        return ScrollGestureClaim(claimantId)
    }

    internal fun claimCurrent(claimantId: Long): String? {
        while (true) {
            val before = snapshot.get()
            val claimed = claimScrollGesture(before, claimantId) ?: return null
            if (claimed === before || snapshot.compareAndSet(before, claimed)) {
                return "${claimed.id}:${claimed.beganAtEpochMs}"
            }
        }
    }

    internal fun unregister(claimantId: Long) {
        listeners.remove(claimantId)
    }

    /** True when release detection can run; false also means preflight declined without prompting. */
    fun isAvailable(): Boolean {
        startIfAvailable()
        return started.get() == true
    }

    private fun startIfAvailable() {
        if (!SystemUtils.isMacOS || started.get() != null) return
        synchronized(this) {
            if (started.get() != null) return
            val available =
                runCatching { Native.load("ApplicationServices", ApplicationServices::class.java) }
                    .mapCatching { api ->
                        if (!api.CGPreflightListenEventAccess()) return@mapCatching false
                        val callback =
                            ScrollEventCallback { _, type, event, _ ->
                                when {
                                    type == CG_EVENT_SCROLL_WHEEL && event != null -> {
                                        consume(api, event)
                                    }

                                    type == CG_EVENT_TAP_DISABLED_BY_TIMEOUT ||
                                        type == CG_EVENT_TAP_DISABLED_BY_USER_INPUT -> {
                                        cancelCurrent()
                                        eventTap.get()?.let { api.CGEventTapEnable(it, true) }
                                    }
                                }
                                event
                            }
                        retainedCallback.set(callback)
                        val tap =
                            api.CGEventTapCreate(1, 0, 1, 1L shl CG_EVENT_SCROLL_WHEEL, callback, null)
                                ?: return@mapCatching false
                        val source =
                            api.CFMachPortCreateRunLoopSource(null, tap, 0)
                                ?: return@mapCatching false
                        eventTap.set(tap)
                        Thread({ runTapSafely(api, tap, source) }, "boss-scroll-phases").apply {
                            isDaemon = true
                            start()
                        }
                        true
                    }.getOrElse { error ->
                        logger.warn(LogCategory.BROWSER, "Native scroll phase observation unavailable", error = error)
                        false
                    }
            started.set(available)
        }
    }

    // Event parsing, atomic transition, publication, and claimant delivery must stay ordered.
    @Suppress("NestedBlockDepth")
    private fun consume(
        api: ApplicationServices,
        event: Pointer,
    ) {
        try {
            val phase = api.CGEventGetIntegerValueField(event, CG_SCROLL_WHEEL_EVENT_SCROLL_PHASE)
            val momentum = api.CGEventGetIntegerValueField(event, CG_SCROLL_WHEEL_EVENT_MOMENTUM_PHASE)
            val dx =
                api
                    .CGEventGetIntegerValueField(event, CG_SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_2)
                    .toDouble()
            val dy =
                api
                    .CGEventGetIntegerValueField(event, CG_SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_1)
                    .toDouble()
            while (true) {
                val before = snapshot.get()
                val (after, ended) =
                    scrollGestureTransition(before, phase, momentum, dx, dy, System.currentTimeMillis())
                if (!snapshot.compareAndSet(before, after)) continue
                if (after != before && after.active) {
                    System.setProperty(PHASE_PROPERTY, "${after.id}:active:${after.beganAtEpochMs}")
                }
                if (ended != null) {
                    val terminal = if (ended.cancelled) "cancelled" else "ended"
                    val terminalEvidence =
                        "${ended.accumX}:${ended.verticalPath}:${ended.rejected}:${ended.reversed}"
                    System.setProperty(
                        PHASE_PROPERTY,
                        "${ended.id}:$terminal:$terminalEvidence",
                    )
                }
                ended?.claimantIds?.forEach { claimantId ->
                    listeners[claimantId]?.let { listener -> runCatching { listener(ended) } }
                }
                return
            }
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: LinkageError) {
            reportEventFailure(error)
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            reportEventFailure(error)
        }
    }

    private fun reportEventFailure(error: Throwable) {
        if (failuresLogged.getAndIncrement() == 0L) {
            logger.warn(LogCategory.BROWSER, "Native scroll phase event failed", error = error)
        }
    }

    private fun cancelCurrent() {
        val before = snapshot.getAndUpdate { it.copy(active = false, observable = false) }
        if (before.active) {
            val end =
                ScrollGestureEnd(
                    before.id,
                    true,
                    before.accumX,
                    before.verticalPath,
                    true,
                    before.reversed,
                    before.claimantIds,
                )
            System.setProperty(
                PHASE_PROPERTY,
                "${end.id}:cancelled:${end.accumX}:${end.verticalPath}:${end.rejected}:${end.reversed}",
            )
            end.claimantIds.forEach { claimantId ->
                listeners[claimantId]?.let { listener -> runCatching { listener(end) } }
            }
        } else {
            System.setProperty(PHASE_PROPERTY, "unavailable")
        }
    }

    private fun runTap(
        api: ApplicationServices,
        tap: Pointer,
        source: Pointer,
    ) {
        val loop = api.CFRunLoopGetCurrent()
        runLoop.set(loop)
        // Run-loop mode constants are exported CFString singleton pointers. A newly allocated
        // equal-looking string is not accepted here; CoreFoundation compares this sentinel by
        // identity and would let CFRunLoopRun return immediately with the tap disabled.
        val mode =
            NativeLibrary
                .getInstance("CoreFoundation")
                .getGlobalVariableAddress("kCFRunLoopCommonModes")
                .getPointer(0)
        api.CFRunLoopAddSource(loop, source, mode)
        api.CGEventTapEnable(tap, true)
        api.CFRunLoopRun()
        started.set(false)
        System.setProperty(PHASE_PROPERTY, "unavailable")
        logger.warn(LogCategory.BROWSER, "Native scroll phase run loop stopped unexpectedly")
    }

    private fun runTapSafely(
        api: ApplicationServices,
        tap: Pointer,
        source: Pointer,
    ) {
        try {
            runTap(api, tap, source)
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: LinkageError) {
            reportRunLoopFailure(error)
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            reportRunLoopFailure(error)
        }
    }

    private fun reportRunLoopFailure(error: Throwable) {
        started.set(false)
        System.setProperty(PHASE_PROPERTY, "unavailable")
        logger.warn(LogCategory.BROWSER, "Native scroll phase run loop failed", error = error)
    }

    const val PHASE_PROPERTY = "boss.browser.swipe.phase"
}

internal class ScrollGestureClaim internal constructor(
    private val claimantId: Long,
) : AutoCloseable {
    fun currentGestureToken(): String? = MacOSScrollGesturePhases.claimCurrent(claimantId)

    override fun close() = MacOSScrollGesturePhases.unregister(claimantId)
}

private fun interface ScrollEventCallback : Callback {
    fun invoke(
        proxy: Pointer?,
        type: Int,
        event: Pointer?,
        refcon: Pointer?,
    ): Pointer?
}

// These names are the native ApplicationServices symbols JNA binds verbatim.
@Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")
private interface ApplicationServices : Library {
    fun CGPreflightListenEventAccess(): Boolean

    fun CGEventTapCreate(
        location: Int,
        placement: Int,
        options: Int,
        mask: Long,
        callback: ScrollEventCallback,
        refcon: Pointer?,
    ): Pointer?

    fun CGEventGetIntegerValueField(
        event: Pointer,
        field: Int,
    ): Long

    fun CGEventTapEnable(
        tap: Pointer,
        enable: Boolean,
    )

    fun CFMachPortCreateRunLoopSource(
        allocator: Pointer?,
        port: Pointer,
        order: Long,
    ): Pointer?

    fun CFRunLoopGetCurrent(): Pointer

    fun CFRunLoopAddSource(
        loop: Pointer,
        source: Pointer,
        mode: Pointer,
    )

    fun CFRunLoopRun()
}

private const val CG_EVENT_SCROLL_WHEEL = 22
private const val CG_EVENT_TAP_DISABLED_BY_TIMEOUT = -2
private const val CG_EVENT_TAP_DISABLED_BY_USER_INPUT = -1
private const val CG_SCROLL_WHEEL_EVENT_SCROLL_PHASE = 99
private const val CG_SCROLL_WHEEL_EVENT_MOMENTUM_PHASE = 123
private const val CG_SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_1 = 96
private const val CG_SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_2 = 97
private const val CG_SCROLL_PHASE_NONE = 0L
private const val CG_SCROLL_PHASE_BEGAN = 1L
private const val CG_SCROLL_PHASE_CHANGED = 2L
private const val CG_SCROLL_PHASE_ENDED = 4L
private const val CG_SCROLL_PHASE_CANCELLED = 8L
private const val CG_SCROLL_PHASE_MAY_BEGIN = 128L
