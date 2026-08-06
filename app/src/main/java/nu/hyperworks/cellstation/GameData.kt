package nu.hyperworks.cellstation

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional online game data, off by default (Settings → Online game data):
 *
 *  - Compatibility statuses from RPCS3's public community list, fetched as
 *    one export and cached for a week — powers the colored dot on tiles,
 *    exactly the feed the desktop app consumes.
 *  - 2D box covers from GameTDB, fetched per serial (BLUS30443-style IDs
 *    from PARAM.SFO — exact match, no name guessing) and cached forever.
 *
 * Everything here is best-effort: any network or parse failure leaves the
 * library exactly as it was, offline-first with the games' own ICON0 art.
 */
object GameData {

    private const val COMPAT_URL = "https://rpcs3.net/compatibility?api=v1&export"
    private const val COMPAT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 10_000

    @Volatile
    private var compat: Map<String, String>? = null

    /** Compatibility status for a serial ("Playable", "Ingame", ...) or null. */
    fun compatStatus(context: Context, serial: String?): String? {
        serial ?: return null
        val map = compat ?: loadCompatFromDisk(context).also { compat = it }
        return map[serial]
    }

    /**
     * Refreshes the compatibility cache if it is missing or stale. Blocking —
     * call from a background thread. Returns true if anything changed.
     */
    fun refreshCompat(context: Context): Boolean {
        val file = compatFile(context)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < COMPAT_MAX_AGE_MS) {
            return false
        }
        val body = fetch(COMPAT_URL) ?: return false
        // Sanity-parse before persisting so a bad download can't wedge the cache.
        val parsed = parseCompat(body) ?: return false
        return try {
            file.writeText(body)
            compat = parsed
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun compatFile(context: Context) = File(context.filesDir, "compat.json")

    private fun loadCompatFromDisk(context: Context): Map<String, String> {
        val file = compatFile(context)
        if (!file.isFile) return emptyMap()
        return try {
            parseCompat(file.readText()) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Export shape: {"return_code":0,"results":{"BLUS30443":{"status":"Playable",...},...}} */
    private fun parseCompat(body: String): Map<String, String>? {
        return try {
            val results = JSONObject(body).optJSONObject("results") ?: return null
            val out = HashMap<String, String>(results.length())
            for (key in results.keys()) {
                results.optJSONObject(key)?.optString("status")?.takeIf { it.isNotEmpty() }?.let {
                    out[key] = it
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    // ---- GameTDB covers ----------------------------------------------------

    fun coverFile(context: Context, serial: String): File =
        File(File(context.filesDir, "covers").apply { mkdirs() }, "$serial.jpg")

    /**
     * Downloads the GameTDB 2D cover for a serial if not already cached.
     * Blocking — call from a background thread. Returns true when a new
     * cover landed on disk.
     */
    fun fetchCover(context: Context, serial: String): Boolean {
        val dest = coverFile(context, serial)
        if (dest.isFile) return false

        // GameTDB shelves PS3 covers by language; the serial's third letter
        // gives the likely shelf, with the big three as fallbacks.
        val guess = when (serial.getOrNull(2)) {
            'U' -> "US"
            'E' -> "EN"
            'J', 'A' -> "JA"
            else -> "US"
        }
        for (region in listOf(guess, "US", "EN", "JA").distinct()) {
            val bytes = fetchBytes("https://art.gametdb.com/ps3/cover/$region/$serial.jpg") ?: continue
            return try {
                dest.writeBytes(bytes)
                true
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    // ---- plumbing ----------------------------------------------------------

    private fun fetch(url: String): String? = fetchBytes(url)?.toString(Charsets.UTF_8)

    private fun fetchBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CellStation")
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
