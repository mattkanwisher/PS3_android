package nu.hyperworks.cellstation

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal PARAM.SFO reader. The format is a small key/value table:
 *
 *   header:  magic "\0PSF", version, key_table_off, data_table_off, count
 *   index:   per entry: key_off u16, fmt u16, len u32, max_len u32, data_off u32
 *
 * Only string values are surfaced (TITLE, TITLE_ID, ...); that is all the
 * library needs. Returns null for anything that doesn't parse cleanly —
 * a corrupt SFO must never take a game out of the list.
 */
object ParamSfo {

    private const val MAGIC = 0x46535000 // "\0PSF" little-endian
    private const val FMT_UTF8 = 0x0204
    private const val MAX_SIZE = 1 shl 20

    fun parse(file: File): Map<String, String>? {
        if (!file.isFile || file.length() > MAX_SIZE) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        return try {
            parseBytes(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBytes(bytes: ByteArray): Map<String, String>? {
        if (bytes.size < 20) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != MAGIC) return null

        val keyTable = buf.getInt(8)
        val dataTable = buf.getInt(12)
        val count = buf.getInt(16)
        if (count !in 0..255 || keyTable < 0 || dataTable < 0) return null

        val out = HashMap<String, String>(count)
        for (i in 0 until count) {
            val idx = 20 + i * 16
            if (idx + 16 > bytes.size) return null
            val keyOff = buf.getShort(idx).toInt() and 0xFFFF
            val fmt = buf.getShort(idx + 2).toInt() and 0xFFFF
            val len = buf.getInt(idx + 4)
            val dataOff = buf.getInt(idx + 12)

            if (fmt != FMT_UTF8) continue
            val keyStart = keyTable + keyOff
            val valStart = dataTable + dataOff
            if (keyStart < 0 || keyStart >= bytes.size || valStart < 0 || len < 0 || valStart + len > bytes.size) continue

            var keyEnd = keyStart
            while (keyEnd < bytes.size && bytes[keyEnd] != 0.toByte()) keyEnd++
            val key = String(bytes, keyStart, keyEnd - keyStart, Charsets.UTF_8)

            var valLen = len
            while (valLen > 0 && bytes[valStart + valLen - 1] == 0.toByte()) valLen--
            out[key] = String(bytes, valStart, valLen, Charsets.UTF_8)
        }
        return out
    }
}
