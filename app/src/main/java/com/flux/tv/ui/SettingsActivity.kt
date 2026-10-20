package com.flux.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.flux.tv.R
import com.flux.tv.data.DnsDiagnostic
import com.flux.tv.data.DnsMode
import com.flux.tv.data.DohProvider
import com.flux.tv.data.FluxDns
import com.flux.tv.data.Prefs
import com.flux.tv.data.Repo
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity() {

    private lateinit var list: LinearLayout
    private var netValue: TextView? = null
    private var profileValue: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        list = findViewById(R.id.list)
        build()
    }

    override fun onResume() {
        super.onResume()
        netValue?.text = networkLabel()
        profileValue?.text = Prefs.profile?.name ?: ""
    }

    private fun build() {
        section("Profil")
        profileValue = row(R.drawable.ic_edit, "Profil actuel", Prefs.profile?.name ?: "") {
            startActivity(Intent(this, ProfilesActivity::class.java).putExtra(ProfilesActivity.EXTRA_SWITCH, true))
        }
        row(R.drawable.ic_history, "Effacer l'historique de visionnage", "") {
            showMenu("Effacer l'historique ?", "« Reprendre la lecture » et les épisodes vus de ce profil seront remis à zéro.", listOf(
                "Effacer" to { Prefs.clearHistory(); toast("Historique effacé") },
                "Annuler" to {},
            ))
        }

        section("Lecture")
        row(R.drawable.ic_next, "Lecture automatique de l'épisode suivant", onOff(Prefs.autoplayNext)) { v ->
            Prefs.autoplayNext = !Prefs.autoplayNext; v.text = onOff(Prefs.autoplayNext)
        }
        row(R.drawable.ic_subtitles, "Taille des sous-titres", subLabel()) { v ->
            Prefs.subtitleSize = (Prefs.subtitleSize + 1) % 3; v.text = subLabel()
        }
        row(R.drawable.ic_live, "Format des chaînes", formatLabel()) { v ->
            Prefs.liveFormat = if (Prefs.liveFormat == "ts") "m3u8" else "ts"; v.text = formatLabel()
        }
        row(R.drawable.ic_speed, "Mémoire tampon", bufferLabel()) { v ->
            Prefs.bufferProfile = (Prefs.bufferProfile + 1) % 3; v.text = bufferLabel()
        }
        row(R.drawable.ic_play, "Aperçu vidéo dans le guide TV", onOff(Prefs.livePreview)) { v ->
            Prefs.livePreview = !Prefs.livePreview; v.text = onOff(Prefs.livePreview)
        }
        row(R.drawable.ic_movie, "Tunneling vidéo (expérimental)", onOff(Prefs.tunneling)) { v ->
            Prefs.tunneling = !Prefs.tunneling; v.text = onOff(Prefs.tunneling)
        }

        section("Compte IPTV")
        row(null, "Serveur", Prefs.server)
        row(null, "Utilisateur", Prefs.user)
        val account = row(null, "Abonnement", "Vérification…")
        lifecycleScope.launch {
            account.text = runCatching { Repo.api().auth() }.fold(
                onSuccess = { a ->
                    val exp = a.expires?.let { "jusqu'au " + SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it * 1000)) } ?: "illimité"
                    "${a.status.replaceFirstChar { it.uppercase() }} · $exp · ${a.active}/${a.max} écrans"
                },
                onFailure = { "Erreur : ${it.message}" },
            )
        }
        row(R.drawable.ic_restart, "Actualiser les chaînes, films et séries", "") {
            Repo.clear(); toast("Catalogue vidé : il sera rechargé")
        }
        row(R.drawable.ic_back, "Se déconnecter", "") {
            showMenu("Se déconnecter ?", "Les identifiants IPTV seront effacés de ce boîtier.", listOf(
                "Se déconnecter" to {
                    Prefs.clearAccount(); Repo.clear(); Session.profileChosen = false
                    startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                },
                "Annuler" to {},
            ))
        }

        section("Réseau · DNS")
        row(R.drawable.ic_wifi, "Résolution DNS", Prefs.dnsMode.label) { v ->
            Prefs.dnsMode = DnsMode.values()[(Prefs.dnsMode.ordinal + 1) % DnsMode.values().size]
            FluxDns.onSettingsChanged(); v.text = Prefs.dnsMode.label
        }
        row(R.drawable.ic_settings, "Résolveur externe (DoH)", Prefs.dohProvider.label) { v ->
            Prefs.dohProvider = DohProvider.values()[(Prefs.dohProvider.ordinal + 1) % DohProvider.values().size]
            FluxDns.onSettingsChanged(); v.text = Prefs.dohProvider.label
        }
        row(R.drawable.ic_search, "Tester la résolution DNS", "DNS box ↔ DNS externe") { v -> dnsTest(v) }

        section("Système")
        netValue = row(R.drawable.ic_wifi, "Réseau", networkLabel()) { open(Settings.ACTION_WIFI_SETTINGS) }
        row(R.drawable.ic_settings, "Paramètres Android", "Wi-Fi, Ethernet, écran, son") { open(Settings.ACTION_SETTINGS) }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        row(R.drawable.ic_info, "À propos", "Flux TV $version · polices Inter & Big Shoulders (OFL)")

        list.post { list.getChildAt(2)?.requestFocus() }
    }

    /** Compare le DNS de la box et le DNS externe pour le serveur IPTV et un domaine témoin. */
    private fun dnsTest(v: TextView) {
        v.text = "Test en cours…"
        lifecycleScope.launch {
            val hosts = listOfNotNull(
                Uri.parse(Prefs.server).host?.takeIf { it.isNotEmpty() && !FluxDns.isIpLiteral(it) },
                "example.com",
            ).distinct()
            val text = hosts.joinToString("\n\n") { DnsDiagnostic.compare(it).format() }
            v.text = "DNS box ↔ DNS externe"
            showMenu("Diagnostic DNS · ${Prefs.dohProvider.label}", text, listOf("Fermer" to {}), subtitleLines = 30)
        }
    }

    private fun section(title: String) {
        list.addView(TextView(this).apply {
            text = title.uppercase()
            typeface = Fonts.bold
            setTextColor(0xFF7A7A7A.toInt())
            textSize = 11f
            letterSpacing = 0.25f
            setPadding(16.dp, 28.dp, 0, 10.dp)
        })
    }

    private fun row(icon: Int?, label: String, value: String, onClick: ((TextView) -> Unit)? = null): TextView {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, list, false)
        if (icon != null) v.findViewById<ImageView>(R.id.icon).apply { setImageResource(icon); show(true) }
        v.findViewById<TextView>(R.id.label).text = label
        val valueView = v.findViewById<TextView>(R.id.value)
        valueView.text = value
        v.setOnClickListener { onClick?.invoke(valueView) }
        list.addView(v)
        return valueView
    }

    private fun onOff(b: Boolean) = if (b) "Activé" else "Désactivé"
    private fun subLabel() = arrayOf("Petits", "Moyens", "Grands")[Prefs.subtitleSize.coerceIn(0, 2)]
    private fun formatLabel() = if (Prefs.liveFormat == "ts") "MPEG-TS (recommandé)" else "HLS (m3u8)"
    private fun bufferLabel() = when (Prefs.bufferProfile) {
        0 -> "Réactif · zapping rapide"
        2 -> "Stable · connexion faible"
        else -> "Équilibré"
    }

    private fun open(action: String) {
        try { startActivity(Intent(action)) } catch (e: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: ActivityNotFoundException) { toast("Réglages indisponibles") }
        }
    }

    @Suppress("DEPRECATION")
    private fun networkLabel(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = cm.activeNetworkInfo ?: return "Non connecté"
        val ip = ipAddress()?.let { " · $it" } ?: ""
        return when (info.type) {
            ConnectivityManager.TYPE_ETHERNET -> "Ethernet$ip"
            ConnectivityManager.TYPE_WIFI -> {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wi = wm.connectionInfo
                val bars = WifiManager.calculateSignalLevel(wi.rssi, 5).coerceIn(0, 4)
                "Wi-Fi ${"▮".repeat(bars)}${"▯".repeat(4 - bars)} ${wi.linkSpeed} Mb/s$ip"
            }
            else -> (info.typeName ?: "Connecté") + ip
        }
    }

    private fun ipAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull()
}
