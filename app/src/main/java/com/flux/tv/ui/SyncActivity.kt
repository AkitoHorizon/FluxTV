package com.flux.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.flux.tv.R
import com.flux.tv.data.CatalogSync
import com.flux.tv.data.CatalogSync.Status
import com.flux.tv.data.Prefs
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Écran de première connexion : télécharge tout le catalogue avant d'entrer dans l'appli.
 * Le chargement continue tant que le serveur envoie des données (aucune limite de durée)
 * et chaque liste est retentée automatiquement en cas de coupure.
 */
class SyncActivity : BaseActivity() {

    private lateinit var steps: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var error: TextView
    private lateinit var buttons: View
    private lateinit var retry: View
    private lateinit var skip: View
    private val rows = ArrayList<Pair<TextView, TextView>>()
    private var left = false
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)
        steps = findViewById(R.id.steps)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        error = findViewById(R.id.error)
        buttons = findViewById(R.id.buttons)
        retry = findViewById(R.id.retry)
        skip = findViewById(R.id.skip)

        retry.setOnClickListener { CatalogSync.start() }
        skip.setOnClickListener { Prefs.catalogReady = true; enterApp() }
        // Pas de retour en arrière pendant le chargement (l'appli est le lanceur du boîtier)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        startedAt = System.currentTimeMillis()
        CatalogSync.start()
        lifecycleScope.launch { CatalogSync.state.collect { render(it) } }
    }

    private fun render(s: CatalogSync.State) {
        if (rows.isEmpty()) s.steps.forEach { addRow(it.label) }
        s.steps.forEachIndexed { i, st ->
            val (label, value) = rows[i]
            value.text = when (st.status) {
                Status.WAITING -> "en attente"
                Status.RUNNING -> if (st.bytes > 0) "${size(st.bytes)}…" else if (st.note.isNotEmpty()) st.note else "le serveur prépare la liste…"
                Status.DONE -> "✓ " + size(st.bytes)
                Status.FAILED -> "✗ échec"
            }
            val color = when (st.status) {
                Status.DONE -> 0xFF46D369.toInt()
                Status.FAILED -> 0xFFFFB86B.toInt()
                Status.RUNNING -> 0xFFF5F5F1.toInt()
                Status.WAITING -> 0xFF7A7A7A.toInt()
            }
            value.setTextColor(color)
            label.setTextColor(if (st.status == Status.WAITING) 0xFF7A7A7A.toInt() else 0xFFF5F5F1.toInt())
        }
        // Avancement : listes terminées + une petite part pour celle en cours
        val running = s.steps.indexOfFirst { it.status == Status.RUNNING }
        val partial = if (running >= 0) 0.5f else 0f
        progress.progress = ((s.doneCount + partial) / s.steps.size * 1000).toInt()

        val minutes = (System.currentTimeMillis() - startedAt) / 60_000
        message.text = s.message + if (s.running && minutes >= 1) " · $minutes min" else ""
        error.text = s.error?.let { "$it\n\nVérifiez la connexion du boîtier puis réessayez : les listes déjà reçues sont conservées." } ?: ""
        error.show(s.error != null)
        val showButtons = s.error != null && !s.running
        if (showButtons && buttons.visibility != View.VISIBLE) {
            buttons.show(true)
            skip.show(s.canSkip)
            retry.requestFocus()
        } else if (!showButtons) buttons.show(false)

        if (s.finished) enterApp()
    }

    private fun addRow(text: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 7.dp, 0, 7.dp)
        }
        val label = TextView(this).apply {
            this.text = text; typeface = Fonts.semibold; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            typeface = Fonts.medium; textSize = 14f; maxLines = 1
            maxWidth = 240.dp
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(label); row.addView(value)
        steps.addView(row)
        rows += label to value
    }

    private fun size(b: Long) = when {
        b >= 1 shl 20 -> String.format(Locale.FRANCE, "%.1f Mo", b / 1048576.0)
        b >= 1024 -> "${b / 1024} Ko"
        else -> "$b o"
    }

    private fun enterApp() {
        if (left) return
        left = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
