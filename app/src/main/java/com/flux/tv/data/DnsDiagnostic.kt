package com.flux.tv.data

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.net.InetAddress

/** Résultat d'une résolution (système ou DoH). */
data class Resolution(val label: String, val ips: List<String>, val ms: Long, val error: String?) {
    val ok get() = error == null && ips.isNotEmpty()
    fun line() = "$label : " + (if (ok) ips.joinToString(", ") else "échec, $error") + " · $ms ms"
}

data class DnsReport(val host: String, val system: Resolution, val doh: Resolution, val verdict: String, val sinkhole: Boolean) {
    fun format() = "$host\n${system.line()}\n${doh.line()}\n→ $verdict"
}

/**
 * Compare, pour un même nom, la réponse du DNS de la box et celle d'un résolveur DoH externe.
 * Les deux requêtes partent en parallèle et le résultat ne dépend pas du mode DNS choisi.
 */
object DnsDiagnostic {
    private const val TAG = "FluxDNS"

    suspend fun compare(host: String, provider: DohProvider = Prefs.dohProvider): DnsReport = withContext(Dispatchers.IO) {
        coroutineScope {
            val sys = async { timed("DNS box") { Dns.SYSTEM.lookup(host) } }
            val doh = async { timed("DNS externe ${provider.label}") { FluxDns.resolveDoh(host, provider) } }
            report(host, sys.await(), doh.await())
        }.also { Log.i(TAG, "diagnostic\n${it.format()}") }
    }

    private inline fun timed(label: String, block: () -> List<InetAddress>): Resolution {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val ips = block().mapNotNull { it.hostAddress }.distinct()
            Resolution(label, ips, SystemClock.elapsedRealtime() - t0, null)
        } catch (e: DohException) {
            Resolution(label, emptyList(), SystemClock.elapsedRealtime() - t0, e.reason)
        } catch (e: Exception) {
            Resolution(label, emptyList(), SystemClock.elapsedRealtime() - t0, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun report(host: String, sys: Resolution, doh: Resolution): DnsReport {
        val sinkhole = sys.ok && sys.ips.all(::isSinkhole) && !(doh.ok && doh.ips.all(::isSinkhole))
        val verdict = when {
            sys.ok && doh.ok && sys.ips.any { it in doh.ips } -> "Réponses concordantes : la box ne modifie pas ce nom."
            sinkhole -> "La box renvoie une adresse de blocage (${sys.ips.first()}). Le DNS externe donne la vraie adresse."
            sys.ok && doh.ok -> "Adresses différentes. Cela peut venir d'un CDN ou d'une redirection par la box : comparez la connexion dans les deux modes."
            !sys.ok && doh.ok -> "La box ne résout pas ce nom alors que le DNS externe y arrive : blocage DNS probable."
            sys.ok && !doh.ok -> "Le DNS externe a échoué (${doh.error}). Le réseau bloque peut-être le résolveur DoH."
            else -> "Les deux résolutions échouent : nom inexistant ou réseau coupé."
        }
        return DnsReport(host, sys, doh, verdict, sinkhole)
    }

    /** Adresses typiques d'un blocage ou d'une redirection DNS locale. */
    fun isSinkhole(ip: String): Boolean {
        if (ip == "0.0.0.0" || ip == "::" || ip == "::1" || ip.startsWith("127.")) return true
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) return true
        val p = ip.split('.')
        return p.size == 4 && p[0] == "172" && (p[1].toIntOrNull() ?: 0) in 16..31
    }
}
