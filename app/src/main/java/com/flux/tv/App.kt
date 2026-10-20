package com.flux.tv

import android.app.Application
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.flux.tv.data.FluxDns
import com.flux.tv.data.Prefs
import com.flux.tv.ui.Fonts
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Fonts.init(this)
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Connexions réutilisées (keep-alive) : zapping et chargements plus rapides
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
            // Résolution DNS : box ou DNS externe (DoH) selon les réglages ; IPv4 en premier dans tous les cas
            .dns(FluxDns)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()

        // Les affiches passent par le même client (donc le même DNS), sans l'User-Agent VLC
        val images = http.newBuilder().apply { interceptors().clear() }.build()
        Glide.get(this).registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(images))
    }

    companion object {
        lateinit var instance: App
            private set
        lateinit var http: OkHttpClient
            private set

        /** Beaucoup de serveurs IPTV n'acceptent que des lecteurs connus. */
        const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
    }
}
