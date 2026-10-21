package com.flux.tv.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Synchronisation complète du catalogue (chaînes, films, séries) à la première connexion.
 *
 * Elle tourne indépendamment de l'écran : quitter ou rouvrir l'écran de chargement ne la
 * relance pas en double. Chaque liste déjà reçue est conservée, donc un nouvel essai reprend
 * là où l'on s'était arrêté. Chaque liste est retentée automatiquement plusieurs fois.
 */
object CatalogSync {
    private const val TAG = "FluxSync"
    private const val ATTEMPTS = 5
    private val BIG = setOf("get_live_streams", "get_vod_streams", "get_series")

    enum class Status { WAITING, RUNNING, DONE, FAILED }

    data class Step(val action: String, val label: String, val status: Status, val bytes: Long = 0, val note: String = "")

    data class State(
        val steps: List<Step>,
        val running: Boolean = false,
        val finished: Boolean = false,
        val error: String? = null,
        val message: String = "",
    ) {
        val doneCount get() = steps.count { it.status == Status.DONE }
        /** Au moins une des trois grandes listes est là : on peut entrer dans l'appli. */
        val canSkip get() = steps.any { it.status == Status.DONE && it.action in BIG }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<State> get() = _state

    private fun initial() = State(XtreamApi.ACTIONS.map { Step(it, XtreamApi.label(it), Status.WAITING) })

    /** Lance (ou reprend) la synchronisation ; sans effet si elle tourne déjà. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = initial()
    }

    private fun update(i: Int, f: (Step) -> Step, message: String? = null) {
        val s = _state.value
        _state.value = s.copy(
            steps = s.steps.mapIndexed { k, st -> if (k == i) f(st) else st },
            message = message ?: s.message,
        )
    }

    private suspend fun run() {
        val api = Repo.api()
        _state.value = initial().copy(running = true, message = "Connexion au serveur…")
        Log.i(TAG, "synchronisation du catalogue : début")
        for ((i, action) in XtreamApi.ACTIONS.withIndex()) {
            val file = api.catalogFile(action)
            if (file.exists()) {
                update(i, { it.copy(status = Status.DONE, bytes = file.length(), note = "déjà reçu") })
                continue
            }
            var attempt = 1
            while (true) {
                update(i, { it.copy(status = Status.RUNNING, bytes = 0, note = "") },
                    "Téléchargement : ${XtreamApi.label(action)}" + if (attempt > 1) " (essai $attempt/$ATTEMPTS)" else "")
                try {
                    val size = api.download(action) { n -> update(i, { it.copy(bytes = n) }) }
                    update(i, { it.copy(status = Status.DONE, bytes = size, note = "") })
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    val why = api.explain(e)
                    if (attempt >= ATTEMPTS) {
                        update(i, { it.copy(status = Status.FAILED, note = why) })
                        _state.value = _state.value.copy(running = false, error = "${XtreamApi.label(action)} : $why",
                            message = "Le téléchargement s'est arrêté après $ATTEMPTS essais.")
                        Log.w(TAG, "abandon sur $action", e)
                        return
                    }
                    val wait = (attempt * 10).coerceAtMost(30)
                    update(i, { it.copy(note = "nouvel essai dans $wait s : $why") },
                        "Le serveur n'a pas répondu, nouvel essai dans $wait s…")
                    delay(wait * 1000L)
                    attempt++
                }
            }
        }
        Prefs.catalogReady = true
        Repo.dropMemoryAsync()
        Log.i(TAG, "synchronisation du catalogue : terminée")
        _state.value = _state.value.copy(running = false, finished = true, message = "Catalogue prêt")
    }
}
