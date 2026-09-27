package nl.jeroen.massqueue

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Houdt het actieve server-adres + token bij, zodat plaatjes van onze eigen server
 * het token als Authorization-header krijgen in plaats van als `?token=` in de URL
 * (dat belandde anders in Coil's cache-sleutels en in serverlogs).
 */
object ServerAuth {
    @Volatile private var base: HttpUrl? = null
    @Volatile private var token: String? = null

    fun update(baseUrl: String, newToken: String?) {
        base = baseUrl.toHttpUrlOrNull()
        token = newToken
    }

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val b = base
        val t = token
        val sameServer = b != null && req.url.scheme == b.scheme &&
            req.url.host == b.host && req.url.port == b.port
        if (sameServer && !t.isNullOrBlank() && req.header("Authorization") == null) {
            chain.proceed(req.newBuilder().header("Authorization", "Bearer $t").build())
        } else {
            chain.proceed(req)
        }
    }
}

class MassApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { OkHttpClient.Builder().addInterceptor(ServerAuth.interceptor).build() }
            .build()
}
