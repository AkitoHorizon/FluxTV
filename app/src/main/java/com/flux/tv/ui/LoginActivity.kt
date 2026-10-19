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
import kotlinx.coroutines.launch
import java.net.ConnectException
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
                XtreamApi(s, u, p).auth()
                Prefs.server = s; Prefs.user = u; Prefs.pass = p
                Repo.clear()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(
                    when (e) {
                        is UnknownHostException -> "Serveur introuvable. Vérifiez l'adresse et la connexion Internet."
                        is SocketTimeoutException -> "Le serveur ne répond pas (délai dépassé)."
                        is ConnectException -> "Connexion refusée par le serveur (port incorrect ?)."
                        is IllegalArgumentException -> "Adresse du serveur invalide."
                        else -> e.message ?: "Erreur de connexion."
                    }
                )
            } finally {
                setBusy(false)
            }
        }
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
