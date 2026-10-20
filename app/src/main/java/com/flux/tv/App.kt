package com.flux.tv

import android.app.Application
import com.flux.tv.data.Prefs
import com.flux.tv.ui.Fonts
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
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
            // IPv4 en premier : beaucoup de box/FAI annoncent l'IPv6 sans le router correctement
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
            })
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
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
