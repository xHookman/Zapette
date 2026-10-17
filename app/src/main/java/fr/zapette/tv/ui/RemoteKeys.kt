package fr.zapette.tv.ui

import android.view.KeyEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Digit of a number key (top row or keypad), or -1. */
fun KeyEvent.digit(): Int = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
    else -> -1
}

fun KeyEvent.isOk() = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

fun KeyEvent.isBack() = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE

/**
 * Channel number typed on the remote: digits are collected for a moment, then
 * [onNumber] is called (or at once after 3 digits). A first "0" means "previous channel".
 */
class NumberEntry(
    private val scope: CoroutineScope,
    private val onTyping: (String) -> Unit,
    private val onNumber: (Int) -> Unit,
) {
    private var typed = ""
    private var job: Job? = null

    fun type(digit: Int) {
        job?.cancel()
        typed = (typed + digit).takeLast(3)
        onTyping(typed)
        if (typed == "0" || typed.length == 3) {
            commit()
        } else {
            job = scope.launch {
                delay(1_400)
                commit()
            }
        }
    }

    fun cancel(): Boolean {
        val wasTyping = typed.isNotEmpty()
        job?.cancel()
        typed = ""
        onTyping("")
        return wasTyping
    }

    private fun commit() {
        val number = typed.toIntOrNull()
        typed = ""
        onTyping("")
        if (number != null) onNumber(number)
    }
}

/**
 * "OK" pressed twice quickly (like TV Mate) brings back the mini-player channel.
 *
 * The first OK is held back a fraction of a second: if a second OK follows, it is
 * a double press; otherwise the held key events are replayed and the focused item
 * reacts normally (click or long press).
 */
class DoubleOk(private val scope: CoroutineScope, private val view: View, private val onDouble: () -> Unit) {
    private var heldDown: KeyEvent? = null
    private var heldUp: KeyEvent? = null
    private var swallowNextUp = false
    private var replaying = false
    private var timer: Job? = null

    /** Returns true when the event is consumed. */
    fun handle(e: KeyEvent): Boolean {
        if (replaying || !e.isOk()) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (!down && swallowNextUp) {          // release of the second press
            swallowNextUp = false
            return true
        }
        if (down && e.repeatCount == 0) swallowNextUp = false
        val pending = heldDown
        if (down) {
            if (e.repeatCount > 0) {           // long press: give the held press back now
                if (pending != null) replay()
                return false
            }
            if (pending != null) {             // second press: double OK
                reset()
                swallowNextUp = true
                onDouble()
                return true
            }
            heldDown = KeyEvent(e)
            timer = scope.launch {
                delay(260)
                replay()
            }
            return true
        }
        if (pending != null) {                 // first press released while held back
            heldUp = KeyEvent(e)
            return true
        }
        return false
    }

    /** Forgets any held press (mini-player closed, player opened…). */
    fun reset() {
        timer?.cancel()
        heldDown = null
        heldUp = null
        swallowNextUp = false
    }

    private fun replay() {
        timer?.cancel()
        val down = heldDown ?: return
        val up = heldUp
        heldDown = null
        heldUp = null
        replaying = true
        try {
            view.dispatchKeyEvent(down)
            if (up != null) view.dispatchKeyEvent(up)
        } finally {
            replaying = false
        }
    }
}
