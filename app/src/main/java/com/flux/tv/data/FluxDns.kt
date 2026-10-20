package com.flux.tv.data

import android.os.SystemClock
import android.util.Log
import com.flux.tv.App
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Comment l'application résout les noms de domaine. */
enum class DnsMode(val label: String) {
    /** Comportement d'origine : résolveur fourni par le réseau (box). */
    SYSTEM("DNS du réseau (box)"),
    /** DNS-over-HTTPS uniquement : erreur explicite si le résolveur est injoignable. */
    DOH("DNS externe (DoH) strict"),
    /** DNS-over-HTTPS, et repli sur le DNS de la box si le résolveur DoH échoue. */
    DOH_FALLBACK("DNS externe (DoH) + repli box"),
}

/**
 * Résolveurs publics compatibles RFC 8484 (format binaire application/dns-message).
 * Les IP d'amorçage évitent de demander le nom du résolveur au DNS de la box.
 */
enum class DohProvider(val label: String, val url: String, val bootstrap: List<String>) {
    CLOUDFLARE(
        "Cloudflare", "https://cloudflare-dns.com/dns-query",
        listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    ),
    QUAD9(
        "Quad9", "https://dns.quad9.net/dns-query",
        listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
    ),
    GOOGLE(
        "Google", "https://dns.google/dns-query",
        listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
    ),
}

/** Échec de résolution DoH, avec une cause lisible. */
class DohException(
    host: String,
    provider: DohProvider,
    val reason: String,
    /** true si le résolveur lui-même n'a pas pu être joint (et non une réponse « domaine inexistant »). */
    val unreachable: Boolean,
    cause: Throwable?,
) : UnknownHostException("DNS externe ${provider.label} : $reason ($host)") {
    init { if (cause != null) initCause(cause) }
}

/**
 * Résolveur DNS de tout le client OkHttp de l'application (API Xtream, lecteur vidéo, images).
 *
 * Seule la *résolution* change : OkHttp se connecte toujours avec le nom d'hôte d'origine,
 * donc l'en-tête Host, le SNI, la validation TLS du certificat, les redirections
 * et le keep-alive fonctionnent exactement comme avant.
 */
object FluxDns : Dns {
    private const val TAG = "FluxDNS"

    @Volatile private var current: Pair<DohProvider, DnsOverHttps>? = null

    /** Dernière voie de résolution utilisée par hôte (pour les messages de diagnostic). */
    private val lastRoute = ConcurrentHashMap<String, String>()

    /** Client dédié aux requêtes DoH : délais courts, petit cache HTTP qui respecte les TTL. */
    private val dohHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .cache(Cache(File(App.instance.cacheDir, "doh"), 512L * 1024))
            .build()
    }

    private fun client(p: DohProvider): DnsOverHttps {
        current?.let { (provider, doh) -> if (provider == p) return doh }
        val doh = DnsOverHttps.Builder()
            .client(dohHttp)
            .url(p.url.toHttpUrl())
            .bootstrapDnsHosts(p.bootstrap.map { InetAddress.getByName(it) })   // IP littérales : aucune requête DNS
            .includeIPv6(true)
            .build()
        current = p to doh
        return doh
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val mode = Prefs.dnsMode
        val result = when {
            mode == DnsMode.SYSTEM || isIpLiteral(hostname) -> system(hostname, "DNS box")
            else -> try {
                resolveDoh(hostname, Prefs.dohProvider).also {
                    lastRoute[hostname] = "DNS externe ${Prefs.dohProvider.label}"
                }
            } catch (e: DohException) {
                if (mode != DnsMode.DOH_FALLBACK) throw e
                Log.w(TAG, "repli sur le DNS de la box pour $hostname : ${e.reason}")
                system(hostname, "DNS box (repli : ${e.reason})")
            }
        }
        // IPv4 en premier : beaucoup de box/FAI annoncent l'IPv6 sans le router correctement
        return result.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    /** Résolution A + AAAA via le résolveur DoH donné, avec journalisation et erreurs explicites. */
    fun resolveDoh(host: String, provider: DohProvider): List<InetAddress> {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val r = client(provider).lookup(host)
            Log.i(TAG, "DoH ${provider.label} $host -> ${r.joinToString { it.hostAddress ?: "?" }} (${SystemClock.elapsedRealtime() - t0} ms)")
            if (r.isEmpty()) throw DohException(host, provider, "aucune adresse renvoyée", false, null)
            return r
        } catch (e: DohException) {
            throw e
        } catch (e: IOException) {
            val (reason, unreachable) = explain(e)
            Log.w(TAG, "DoH ${provider.label} $host échec en ${SystemClock.elapsedRealtime() - t0} ms : $reason", e)
            throw DohException(host, provider, reason, unreachable, e)
        }
    }

    /** Voie utilisée lors de la dernière résolution de cet hôte (null si jamais résolu). */
    fun routeFor(host: String): String? = lastRoute[host]

    /** À appeler quand le mode ou le résolveur change : les connexions ouvertes sont refaites. */
    fun onSettingsChanged() {
        lastRoute.clear()
        App.http.connectionPool.evictAll()
        Log.i(TAG, "mode = ${Prefs.dnsMode}, résolveur = ${Prefs.dohProvider.label}")
    }

    private fun system(host: String, route: String): List<InetAddress> {
        val r = Dns.SYSTEM.lookup(host)
        lastRoute[host] = route
        Log.d(TAG, "$route $host -> ${r.joinToString { it.hostAddress ?: "?" }}")
        return r
    }

    /** Traduit l'exception OkHttp (et ses causes ou exceptions supprimées) en cause lisible. */
    private fun explain(e: Throwable): Pair<String, Boolean> {
        val all = generateSequence(e) { it.cause }.flatMap { sequenceOf(it) + it.suppressed.asSequence() }
            .take(20).toList()
        val text = all.joinToString(" ") { it.message.orEmpty() }
        return when {
            text.contains("NXDOMAIN") -> "domaine inexistant (NXDOMAIN)" to false
            text.contains("SERVFAIL") -> "le résolveur n'a pas pu résoudre ce nom (SERVFAIL)" to false
            all.any { it is SSLException } -> "échec TLS avec le résolveur (certificat refusé ou connexion interceptée)" to true
            all.any { it is InterruptedIOException } -> "délai dépassé, résolveur injoignable" to true
            all.any { it is ConnectException || it is NoRouteToHostException } -> "résolveur injoignable (connexion refusée ou bloquée)" to true
            text.contains("response:") -> "réponse HTTP invalide du résolveur" to true
            all.any { it is UnknownHostException } -> "aucune adresse renvoyée" to false
            else -> (e.message ?: e.javaClass.simpleName) to true
        }
    }

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    fun isIpLiteral(host: String) = host.contains(':') || ipv4.matches(host)
}
