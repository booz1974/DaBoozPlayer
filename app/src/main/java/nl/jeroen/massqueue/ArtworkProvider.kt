package nl.jeroen.massqueue

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Hoezen voor Android Auto (lijsten en wachtrij).
 *
 * Android Auto laadt artwork-URI's zelf en kent ons API-token niet; MA-afbeeldingen via het
 * server-adres zouden dan niet laden. Deze provider haalt ze op met [ServerAuth] (token alleen
 * naar de eigen server) en bewaart ze in de cache.
 *
 * Alleen antwoorden met Content-Type image/ worden doorgegeven, zodat andere apps via deze
 * provider nooit iets anders dan plaatjes van de server kunnen lezen.
 */
class ArtworkProvider : ContentProvider() {

    private val http by lazy {
        OkHttpClient.Builder()
            .addInterceptor(ServerAuth.interceptor)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Alleen lezen")
        val url = uri.getQueryParameter(PARAM_URL)
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: throw FileNotFoundException(uri.toString())
        val dir = File(context!!.cacheDir, CACHE_DIR).apply { mkdirs() }
        val file = File(dir, sha256(url))
        if (!file.exists()) download(url, file, dir)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun download(url: String, file: File, dir: File) {
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val body = resp.body
                val isImage = resp.header("Content-Type")?.startsWith("image/") == true
                if (!resp.isSuccessful || body == null || !isImage) throw FileNotFoundException(url)
                if (body.contentLength() > MAX_BYTES) throw FileNotFoundException(url)
                val tmp = File(dir, file.name + ".tmp")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                tmp.renameTo(file)
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException("$url: ${e.message}")
        }
        trimCache(dir)
    }

    /** Houdt de cache klein: de oudste bestanden eruit boven [MAX_FILES]. */
    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.takeIf { it.size > MAX_FILES } ?: return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val PARAM_URL = "u"
        private const val CACHE_DIR = "auto_artwork"
        private const val MAX_FILES = 300
        private const val MAX_BYTES = 5L * 1024 * 1024

        /** content://-URI voor Android Auto, of null als er geen (http)-afbeelding is. */
        fun uriFor(imageUrl: String?): Uri? {
            if (imageUrl.isNullOrBlank() || !imageUrl.startsWith("http")) return null
            return Uri.Builder()
                .scheme("content")
                .authority(BuildConfig.APPLICATION_ID + ".artwork")
                .path("img")
                .appendQueryParameter(PARAM_URL, imageUrl)
                .build()
        }
    }
}
