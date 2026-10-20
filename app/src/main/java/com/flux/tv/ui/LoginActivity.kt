package com.flux.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.flux.tv.R
import com.flux.tv.data.Prefs
import com.flux.tv.data.Repo
import com.flux.tv.data.XtreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LoginActivity : BaseActivity() {

    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var error: TextView
    private lateinit var login: View
    private lateinit var loginText: TextView
    private lateinit var busy: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        server = findViewById(R.id.server)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)
        error = findViewById(R.id.error)
        login = findViewById(R.id.login)
        loginText = findViewById(R.id.loginText)
        busy = findViewById(R.id.busy)

        server.setText(Prefs.server)
        user.setText(Prefs.user)
        login.setOnClickListener { submit() }
        // Lien M3U complet collé dans le champ serveur : OK suffit pour se connecter
        server.setOnEditorActionListener { _, _, _ ->
            if (server.text.contains("username=")) { submit(); true } else false
        }
        pass.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { login.requestFocus(); submit(); true } else false
        }
        server.requestFocus()
    }

    private fun submit() {
        var s = server.text.toString().trim()
        var u = user.text.toString().trim()
        var p = pass.text.toString().trim()

        // Lien M3U complet collé dans le champ serveur → on extrait tout
        if (s.contains("username=")) {
            val uri = Uri.parse(if (s.startsWith("http")) s else "http://$s")
            uri.getQueryParameter("username")?.let { u = it; user.setText(it) }
            uri.getQueryParameter("password")?.let { p = it; pass.setText(it) }
            s = "${uri.scheme}://${uri.encodedAuthority}"
        }
        s = normalize(s)
        if (s.isEmpty() || u.isEmpty() || p.isEmpty()) { showError("Remplissez les trois champs."); return }
        server.setText(s)

        lifecycleScope.launch {
            setBusy(true)
            try {
                val ok = tryVariants(s, u, p)
                withContext(Dispatchers.IO) { Prefs.saveAccount(ok, u, p) }
                Repo.clear()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "Erreur de connexion.")
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * Essaie l'adresse telle quelle puis des variantes courantes (sans :80, https, port 8080).
     * Renvoie l'adresse qui a fonctionné, ou lève une erreur détaillée.
     */
    private suspend fun tryVariants(s: String, u: String, p: String): String {
        val list = variants(s)
        var authError: Exception? = null
        var firstError: Exception? = null
        for ((i, v) in list.withIndex()) {
            if (i > 0) showError("Essai ${i + 1}/${list.size} : $v")
            try {
                XtreamApi(v, u, p).auth()
                return v
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("FluxQA", "login $v -> $e")
                if (firstError == null) firstError = e
                if (authError == null && e.message?.contains("incorrect") == true) authError = e
            }
        }
        authError?.let { throw it }
        val e = firstError ?: Exception("Erreur de connexion.")
        val host = Uri.parse(s).host
        val ips = withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(host).map { it.hostAddress } }.getOrNull()
        }
        val blocked = ips?.any { it == "0.0.0.0" || it.startsWith("127.") || it == "::" || it == "::1" } == true
        val msg = when {
            blocked -> "L'adresse du serveur est bloquée par le DNS de votre réseau (elle pointe vers ${ips?.first()})."
            e is UnknownHostException -> "Serveur introuvable. Vérifiez l'adresse et la connexion Internet."
            e is SocketTimeoutException -> "Le serveur ne répond pas (délai dépassé)."
            e is ConnectException -> "Connexion refusée par le serveur."
            e is IllegalArgumentException -> "Adresse du serveur invalide."
            else -> e.message ?: "Erreur de connexion."
        }
        val detail = "Détail : ${e.javaClass.simpleName} ${e.message ?: ""}".trim() +
            (ips?.let { "\nIP du serveur : " + it.joinToString(", ") } ?: "") +
            "\nAdresses essayées : " + list.joinToString(" · ")
        throw Exception("$msg\n\n$detail")
    }

    private fun variants(s: String): List<String> {
        val uri = Uri.parse(s)
        val host = uri.host ?: return listOf(s)
        val h = if (host.contains(':')) "[$host]" else host
        val out = LinkedHashSet<String>()
        out += s
        when (uri.port) {
            80 -> out += "http://$h"
            -1 -> out += "http://$h:8080"
            else -> {}
        }
        if (uri.scheme == "http") out += "https://$h" + if (uri.port != -1 && uri.port != 80) ":${uri.port}" else ""
        else out += "http://$h" + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
        return out.toList()
    }

    private fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        for (suffix in listOf("/player_api.php", "/get.php", "/c")) if (s.endsWith(suffix)) s = s.removeSuffix(suffix)
        return s.trimEnd('/')
    }

    private fun showError(msg: String) {
        error.text = msg
        error.fade(true)
    }

    private fun setBusy(b: Boolean) {
        busy.show(b)
        loginText.show(!b)
        login.isEnabled = !b
        if (b) error.show(false)
    }
}
