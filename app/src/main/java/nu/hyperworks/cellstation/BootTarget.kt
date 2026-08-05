package nu.hyperworks.cellstation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Resolves whatever a frontend hands us into a filesystem path the core can open.
 *
 * The core opens games by path (disc images are gigabytes; streaming them through
 * a content provider is not viable), while launchers speak `content://`. With
 * MANAGE_EXTERNAL_STORAGE granted we can map the common document URIs back onto
 * real paths instead of copying.
 */
object BootTarget {

    /** Returns a readable path, or null if the target can't be resolved. */
    fun resolve(context: Context, intent: Intent): String? {
        intent.getStringExtra(EmulationActivity.EXTRA_GAME_DIR)?.let { dir ->
            resolveGameDir(dir)?.let { return it }
        }

        intent.getStringExtra(EmulationActivity.EXTRA_BOOT_PATH)?.let { raw ->
            fromRawString(context, raw)?.let { return it }
        }

        // ACTION_VIEW and other data-carrying launches.
        intent.data?.let { uri ->
            fromUri(context, uri)?.let { return it }
        }

        return null
    }

    private fun fromRawString(context: Context, raw: String): String? {
        // Frontends pass either a plain path or a URI in the same extra.
        if (!raw.contains("://")) {
            return raw.takeIf { File(it).exists() }
        }
        return fromUri(context, Uri.parse(raw))
    }

    fun fromUri(context: Context, uri: Uri): String? {
        when (uri.scheme) {
            "file" -> return uri.path?.takeIf { File(it).exists() }

            "content" -> {
                pathFromDocumentUri(uri)?.let { return it }

                // Last resort: copy into cache. Only sane for small payloads —
                // homebrew, not disc images.
                return copyToCache(context, uri)
            }
        }
        return uri.path?.takeIf { File(it).exists() }
    }

    /**
     * Maps Storage Access Framework document URIs onto real paths.
     * `content://com.android.externalstorage.documents/document/primary%3APS3%2Fgame.iso`
     * becomes `/storage/emulated/0/PS3/game.iso`.
     */
    private fun pathFromDocumentUri(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null

        val docId = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }
            .getOrNull() ?: return null
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2) return null

        val (volume, relative) = parts
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return File(base, relative).takeIf { it.exists() }?.absolutePath
    }

    private fun copyToCache(context: Context, uri: Uri): String? = runCatching {
        val target = File(context.cacheDir, "boot-target.bin")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        } ?: return null
        target.absolutePath
    }.getOrNull()

    /** A folder-format game: hand the core the executable inside it. */
    private fun resolveGameDir(dir: String): String? {
        val root = File(dir)
        if (!root.isDirectory) return null
        return GameLibrary.classify(root)?.bootPath
    }
}
