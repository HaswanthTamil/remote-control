package com.remotecontrol.ui.remote

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Touch gesture recogniser for the laptop screen stage - a direct port of the
 * touch handlers in `remote_control_phone/js/remote.js`:
 *
 *   move              -> pointer.move (absolute, throttled to ~1 frame)
 *   tap               -> left click
 *   hold & drag       -> left-drag (button down on hold, up on release)
 *   two-finger swipe  -> pointer.scroll
 */
class ScreenGestureHandler(
    val onMove: (Offset) -> Unit,
    val onTap: (Offset) -> Unit,
    val onScroll: (Float) -> Unit,
    val onPressStart: () -> Unit,
    val onPressEnd: () -> Unit,
)

private enum class Mode { IDLE, PENDING, DRAG, MULTI }

private const val TAP_TIMEOUT_MS = 280L
private const val HOLD_TO_DRAG_MS = 430L
private const val MOVE_THROTTLE_MS = 16L
private const val SCROLL_FACTOR = 1.5f

fun Modifier.laptopScreenGestures(
    dragThresholdPx: Float,
    /** Only touches inside the displayed laptop screen drive the laptop pointer. */
    mappable: (Offset) -> Boolean,
    handler: ScreenGestureHandler,
): Modifier = pointerInput(dragThresholdPx, mappable, handler) {
    val longPressMs = viewConfiguration.longPressTimeoutMillis.takeIf { it > 0 } ?: HOLD_TO_DRAG_MS
    val dragThreshold = if (dragThresholdPx > 0f) dragThresholdPx else viewConfiguration.touchSlop.toFloat()

    awaitPointerEventScope {
        val active = mutableMapOf<PointerId, Offset>()
        var mode = Mode.IDLE
        var primaryId: PointerId? = null
        var startPos = Offset.Zero
        var downAt = 0L
        var lastMoveAt = 0L
        var pointerHeld = false

        fun releasePointer() {
            if (pointerHeld) {
                handler.onPressEnd()
                pointerHeld = false
            }
        }

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val now = System.currentTimeMillis()

            // Snapshot this event's pointers plus each pointer's movement.
            val deltas = mutableMapOf<PointerId, Offset>()
            for (change in event.changes) {
                if (change.pressed) {
                    val previous = active[change.id]
                    if (previous != null) deltas[change.id] = change.position - previous
                    active[change.id] = change.position
                } else {
                    active.remove(change.id)
                }
            }

            val down = event.changes.firstOrNull { it.changedToDown() }
            val up = event.changes.firstOrNull { it.changedToUp() }
            val primaryPosition = primaryId?.let { active[it] }

            // Begin: a finger went down.
            if (down != null && active.isNotEmpty()) {
                startPos = down.position
                downAt = now
                primaryId = down.id
                mode = if (mappable(down.position)) Mode.PENDING else Mode.IDLE
            }

            // A second finger always wins: scroll, and drop any pending press.
            if (active.size >= 2) {
                releasePointer()
                mode = Mode.MULTI
            }

            when {
                active.size >= 2 -> {
                    var dy = 0f
                    for ((id, delta) in deltas) {
                        if (active.containsKey(id)) dy += delta.y
                    }
                    val insideScreen = active.values.all { mappable(it) }
                    if (dy != 0f && insideScreen) handler.onScroll(dy * SCROLL_FACTOR)
                }

                mode == Mode.PENDING || mode == Mode.DRAG -> {
                    val position = primaryPosition
                    if (position == null || !mappable(position)) {
                        // Left the displayed screen: drop the press instead of
                        // dragging along the edge.
                        releasePointer()
                        mode = Mode.PENDING
                    } else {
                        val distance = (position - startPos).getDistance()
                        if (mode == Mode.PENDING &&
                            (distance > dragThreshold || now - downAt >= longPressMs)
                        ) {
                            mode = Mode.DRAG
                            handler.onPressStart()
                        }
                        if (now - lastMoveAt >= MOVE_THROTTLE_MS) {
                            lastMoveAt = now
                            handler.onMove(position)
                        }
                    }
                }
            }

            // End: the last finger lifted.
            if (up != null) {
                if (active.isEmpty()) {
                    val elapsed = now - downAt
                    val onScreen = mappable(up.position)
                    when {
                        mode == Mode.PENDING && elapsed < TAP_TIMEOUT_MS && onScreen -> {
                            handler.onMove(up.position)
                            handler.onTap(up.position)
                        }

                        pointerHeld && onScreen -> {
                            handler.onMove(up.position)
                            releasePointer()
                        }

                        pointerHeld -> releasePointer()
                    }
                    mode = Mode.IDLE
                    primaryId = null
                } else if (pointerHeld) {
                    // One finger lifted mid-drag while another is still down.
                    mode = Mode.DRAG
                    primaryId = active.keys.firstOrNull()
                }
            }

            event.changes.forEach { change ->
                if (change.positionChanged()) change.consume()
            }
        }
    }
}