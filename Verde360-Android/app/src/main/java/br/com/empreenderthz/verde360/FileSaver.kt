package br.com.empreenderthz.verde360

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Grava arquivos gerados pela página (relatos e análises exportados como blob:/data:).
 * Android 10+: pasta pública Downloads via MediaStore, sem permissão.
 * Android 8 e 9: pasta Download do próprio app, compartilhável via FileProvider.
 */
class FileSaver(private val context: Context) {

    sealed class Result {
        data class Saved(val uri: Uri, val name: String, val mime: String) : Result()
        object TooLarge : Result()
        data class Failed(val error: Throwable?) : Result()
    }

    fun saveDataUrl(dataUrl: String, rawName: String, rawMime: String): Result {
        try {
            val comma = dataUrl.indexOf(',')
            if (!dataUrl.startsWith("data:") || comma < 0) return Result.Failed(null)
            val header = dataUrl.substring(5, comma)
            val payload = dataUrl.substring(comma + 1)
            // Estimativa antes de decodificar, para não alocar memória à toa
            if (payload.length > MAX_BYTES / 3 * 4 + 4) return Result.TooLarge
            val bytes = if (header.endsWith(";base64")) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
            if (bytes.size > MAX_BYTES) return Result.TooLarge

            val headerMime = header.substringBefore(';')
            val mime = rawMime.ifBlank { headerMime.ifBlank { "application/octet-stream" } }
            val name = sanitize(rawName, mime)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(bytes, name, mime)
            } else {
                saveToAppFolder(bytes, name)
            }
            return Result.Saved(uri, name, mime)
        } catch (e: Exception) {
            return Result.Failed(e)
        } catch (e: OutOfMemoryError) {
            return Result.TooLarge
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(bytes: ByteArray, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Não foi possível criar o arquivo em Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Fluxo de gravação indisponível")
            out.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun saveToAppFolder(bytes: ByteArray, name: String): Uri {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Pasta de downloads indisponível")
        val file = uniqueFile(dir, name)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (file.exists()) {
            file = File(dir, "$base ($i)$ext")
            i++
        }
        return file
    }

    private fun sanitize(rawName: String, mime: String): String {
        var n = rawName.substringAfterLast('/')
            .replace(INVALID_CHARS, "_")
            .trim()
            .trim('.')
        if (n.isEmpty()) n = "verde360-arquivo"
        if (n.length > 100) n = n.take(100)
        if (!n.contains('.')) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (ext != null) n += ".$ext"
        }
        return n
    }

    companion object {
        const val MAX_BYTES = 25 * 1024 * 1024
        private val INVALID_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    }
}
