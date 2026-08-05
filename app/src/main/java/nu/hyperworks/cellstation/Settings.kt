package nu.hyperworks.cellstation

import android.content.Context

/** App-side preferences (emulator settings live in the core's config.yml). */
object Settings {

    /** When the on-screen controller is shown. */
    enum class TouchOverlayMode {
        /** Shown until a physical controller is used, then hidden. */
        AUTO,
        ALWAYS,
        NEVER;

        companion object {
            fun from(name: String?): TouchOverlayMode =
                entries.firstOrNull { it.name == name } ?: AUTO
        }
    }

    private const val PREFS = "cellstation"
    private const val KEY_TOUCH_OVERLAY = "touch_overlay_mode"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun touchOverlayMode(context: Context): TouchOverlayMode =
        TouchOverlayMode.from(prefs(context).getString(KEY_TOUCH_OVERLAY, null))

    fun setTouchOverlayMode(context: Context, mode: TouchOverlayMode) {
        prefs(context).edit().putString(KEY_TOUCH_OVERLAY, mode.name).apply()
    }
}
