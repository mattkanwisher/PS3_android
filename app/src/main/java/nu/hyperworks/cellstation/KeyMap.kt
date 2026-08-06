package nu.hyperworks.cellstation

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Physical-button → PS3-control mapping. The default is the standard layout
 * every emulator ships (A=Cross, B=Circle, X=Square, Y=Triangle); a "swapped"
 * preset covers Nintendo-labeled pads where the positions are mirrored.
 * Stored as JSON in prefs; sticks and analog triggers stay axis-driven and
 * are not remappable here.
 */
object KeyMap {

    /** PS3 controls a key can be bound to, in display order. */
    val TARGETS: List<Pair<Int, String>> = listOf(
        PadState.Btn.CROSS to "Cross ✕",
        PadState.Btn.CIRCLE to "Circle ○",
        PadState.Btn.SQUARE to "Square □",
        PadState.Btn.TRIANGLE to "Triangle △",
        PadState.Btn.L1 to "L1",
        PadState.Btn.R1 to "R1",
        PadState.Btn.L2 to "L2",
        PadState.Btn.R2 to "R2",
        PadState.Btn.L3 to "L3 (stick click)",
        PadState.Btn.R3 to "R3 (stick click)",
        PadState.Btn.START to "Start",
        PadState.Btn.SELECT to "Select",
        PadState.Btn.PS to "PS button",
        PadState.Btn.UP to "D-pad Up",
        PadState.Btn.DOWN to "D-pad Down",
        PadState.Btn.LEFT to "D-pad Left",
        PadState.Btn.RIGHT to "D-pad Right"
    )

    /** Standard layout: positional match with an Xbox/PS-labeled pad. */
    val DEFAULT: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to PadState.Btn.CROSS,
        KeyEvent.KEYCODE_BUTTON_B to PadState.Btn.CIRCLE,
        KeyEvent.KEYCODE_BUTTON_X to PadState.Btn.SQUARE,
        KeyEvent.KEYCODE_BUTTON_Y to PadState.Btn.TRIANGLE,
        KeyEvent.KEYCODE_BUTTON_L1 to PadState.Btn.L1,
        KeyEvent.KEYCODE_BUTTON_R1 to PadState.Btn.R1,
        KeyEvent.KEYCODE_BUTTON_L2 to PadState.Btn.L2,
        KeyEvent.KEYCODE_BUTTON_R2 to PadState.Btn.R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL to PadState.Btn.L3,
        KeyEvent.KEYCODE_BUTTON_THUMBR to PadState.Btn.R3,
        KeyEvent.KEYCODE_BUTTON_START to PadState.Btn.START,
        KeyEvent.KEYCODE_MENU to PadState.Btn.START,
        KeyEvent.KEYCODE_BUTTON_SELECT to PadState.Btn.SELECT,
        KeyEvent.KEYCODE_BACK to PadState.Btn.SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE to PadState.Btn.PS,
        KeyEvent.KEYCODE_DPAD_UP to PadState.Btn.UP,
        KeyEvent.KEYCODE_DPAD_DOWN to PadState.Btn.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to PadState.Btn.LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to PadState.Btn.RIGHT
    )

    /** Nintendo-labeled pads: face buttons are positionally mirrored. */
    val SWAPPED: Map<Int, Int> = DEFAULT + mapOf(
        KeyEvent.KEYCODE_BUTTON_A to PadState.Btn.CIRCLE,
        KeyEvent.KEYCODE_BUTTON_B to PadState.Btn.CROSS,
        KeyEvent.KEYCODE_BUTTON_X to PadState.Btn.TRIANGLE,
        KeyEvent.KEYCODE_BUTTON_Y to PadState.Btn.SQUARE
    )

    private const val KEY = "key_map"

    fun load(context: Context): Map<Int, Int> {
        val raw = context.getSharedPreferences("cellstation", Context.MODE_PRIVATE)
            .getString(KEY, null)
            // No custom map: the face-buttons setting (Settings → Controls)
            // picks between the two stock layouts.
            ?: return if (Settings.nintendoLayout(context)) SWAPPED else DEFAULT
        return try {
            val json = JSONObject(raw)
            val out = HashMap<Int, Int>(json.length())
            for (key in json.keys()) {
                out[key.toInt()] = json.getInt(key)
            }
            out.ifEmpty { DEFAULT }
        } catch (_: Exception) {
            DEFAULT
        }
    }

    fun save(context: Context, map: Map<Int, Int>) {
        val json = JSONObject()
        for ((code, target) in map) json.put(code.toString(), target)
        context.getSharedPreferences("cellstation", Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences("cellstation", Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    /** Human name for a keycode: "KEYCODE_BUTTON_A" → "Button A". */
    fun keyName(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }

    /** Keys currently bound to a target, joined for display. */
    fun boundKeys(map: Map<Int, Int>, target: Int): String =
        map.filterValues { it == target }.keys
            .joinToString(", ") { keyName(it) }
}
