package nu.hyperworks.cellstation

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Translates Android controller events into the flat snapshot the native pad
 * handler reads. Indices must stay in sync with android_pad_button in
 * native/bridge/android_pad_handler.h.
 */
class PadState {

    // Keep in sync with android_pad_button (native/bridge/android_pad_handler.h)
    private object Btn {
        const val CROSS = 1; const val CIRCLE = 2; const val SQUARE = 3; const val TRIANGLE = 4
        const val L1 = 5; const val R1 = 6; const val L3 = 7; const val R3 = 8
        const val START = 9; const val SELECT = 10; const val PS = 11
        const val UP = 12; const val DOWN = 13; const val LEFT = 14; const val RIGHT = 15
        const val L2 = 16; const val R2 = 17
        const val LS_X_NEG = 18; const val LS_X_POS = 19; const val LS_Y_NEG = 20; const val LS_Y_POS = 21
        const val RS_X_NEG = 22; const val RS_X_POS = 23; const val RS_Y_NEG = 24; const val RS_Y_POS = 25
        const val COUNT = 26
    }

    private val values = ByteArray(Btn.COUNT)

    private fun set(index: Int, value: Int) {
        values[index] = value.coerceIn(0, 255).toByte()
    }

    private fun keyIndex(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> Btn.CROSS
        KeyEvent.KEYCODE_BUTTON_B -> Btn.CIRCLE
        KeyEvent.KEYCODE_BUTTON_X -> Btn.SQUARE
        KeyEvent.KEYCODE_BUTTON_Y -> Btn.TRIANGLE
        KeyEvent.KEYCODE_BUTTON_L1 -> Btn.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> Btn.R1
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Btn.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Btn.R3
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> Btn.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> Btn.SELECT
        KeyEvent.KEYCODE_BUTTON_MODE -> Btn.PS
        KeyEvent.KEYCODE_DPAD_UP -> Btn.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Btn.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Btn.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Btn.RIGHT
        // Some pads report triggers as buttons rather than axes
        KeyEvent.KEYCODE_BUTTON_L2 -> Btn.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> Btn.R2
        else -> -1
    }

    /** Returns true if the event was a controller input we consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isFromGamepad(event.source)) return false
        val index = keyIndex(event.keyCode)
        if (index < 0) return false

        set(index, if (event.action == KeyEvent.ACTION_DOWN) 255 else 0)
        push()
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!isFromGamepad(event.source)) return false

        axisPair(event, MotionEvent.AXIS_X, Btn.LS_X_NEG, Btn.LS_X_POS)
        // Android's Y axis grows downward; the PS3 stick grows upward.
        axisPair(event, MotionEvent.AXIS_Y, Btn.LS_Y_POS, Btn.LS_Y_NEG)
        axisPair(event, MotionEvent.AXIS_Z, Btn.RS_X_NEG, Btn.RS_X_POS)
        axisPair(event, MotionEvent.AXIS_RZ, Btn.RS_Y_POS, Btn.RS_Y_NEG)

        // Triggers: prefer the dedicated axes, falling back to gas/brake.
        set(Btn.L2, trigger(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE))
        set(Btn.R2, trigger(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS))

        // Many pads report the d-pad as a hat rather than as key events.
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        set(Btn.LEFT, if (hatX < -0.5f) 255 else 0)
        set(Btn.RIGHT, if (hatX > 0.5f) 255 else 0)
        set(Btn.UP, if (hatY < -0.5f) 255 else 0)
        set(Btn.DOWN, if (hatY > 0.5f) 255 else 0)

        push()
        return true
    }

    private fun axisPair(event: MotionEvent, axis: Int, negIndex: Int, posIndex: Int) {
        val v = event.getAxisValue(axis)
        val magnitude = (abs(v) * 255f).roundToInt()
        set(negIndex, if (v < 0f) magnitude else 0)
        set(posIndex, if (v > 0f) magnitude else 0)
    }

    private fun trigger(event: MotionEvent, primary: Int, fallback: Int): Int {
        val v = maxOf(event.getAxisValue(primary), event.getAxisValue(fallback))
        return (v.coerceIn(0f, 1f) * 255f).roundToInt()
    }

    private fun push() = EmuBridge.setPadState(values)

    companion object {
        fun isFromGamepad(source: Int): Boolean =
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
    }
}
