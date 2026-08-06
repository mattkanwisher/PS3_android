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
    private const val KEY_SCAN_FOLDERS = "scan_folders"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun touchOverlayMode(context: Context): TouchOverlayMode =
        TouchOverlayMode.from(prefs(context).getString(KEY_TOUCH_OVERLAY, null))

    fun setTouchOverlayMode(context: Context, mode: TouchOverlayMode) {
        prefs(context).edit().putString(KEY_TOUCH_OVERLAY, mode.name).apply()
    }

    /** Extra folders the user pointed us at, on top of the conventional roots. */
    fun scanFolders(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SCAN_FOLDERS, emptySet()).orEmpty()

    fun addScanFolder(context: Context, path: String) {
        val updated = scanFolders(context) + path
        prefs(context).edit().putStringSet(KEY_SCAN_FOLDERS, updated).apply()
    }

    fun removeScanFolder(context: Context, path: String) {
        val updated = scanFolders(context) - path
        prefs(context).edit().putStringSet(KEY_SCAN_FOLDERS, updated).apply()
    }

    /** Directory name under gpu_drivers/ of the selected driver; "" = system. */
    fun gpuDriver(context: Context): String =
        prefs(context).getString(KEY_GPU_DRIVER, "").orEmpty()

    fun setGpuDriver(context: Context, dirName: String) {
        prefs(context).edit().putString(KEY_GPU_DRIVER, dirName).apply()
    }

    /** Boot path of the last game launched, for the library's Continue card. */
    fun lastPlayed(context: Context): String =
        prefs(context).getString(KEY_LAST_PLAYED, "").orEmpty()

    fun setLastPlayed(context: Context, bootPath: String) {
        prefs(context).edit()
            .putString(KEY_LAST_PLAYED, bootPath)
            .putLong(KEY_LAST_PLAYED_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastPlayedAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_PLAYED_AT, 0L)

    /** True once the first-run wizard has been completed or skipped. */
    fun setupDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    /** Boot paths the user removed from the library view (files stay on disk). */
    fun hiddenGames(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN_GAMES, emptySet()).orEmpty()

    fun hideGame(context: Context, bootPath: String) {
        prefs(context).edit()
            .putStringSet(KEY_HIDDEN_GAMES, hiddenGames(context) + bootPath)
            .apply()
    }

    fun unhideAllGames(context: Context) {
        prefs(context).edit().remove(KEY_HIDDEN_GAMES).apply()
    }

    private const val KEY_GPU_DRIVER = "gpu_driver"
    private const val KEY_LAST_PLAYED = "last_played"
    private const val KEY_LAST_PLAYED_AT = "last_played_at"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_HIDDEN_GAMES = "hidden_games"
}
