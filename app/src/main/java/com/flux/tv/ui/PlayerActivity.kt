package com.flux.tv.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flux.tv.R
import com.flux.tv.data.PlayItem
import com.flux.tv.data.Prefs
import com.flux.tv.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lecteur plein écran façon Netflix.
 * Films/séries : contrôles maison, avance/retour accéléré, épisode suivant avec compte à rebours,
 * "Vous regardez toujours ?", audio & sous-titres, vitesse, reprise automatique.
 * Direct : zapping, numéros de chaîne, bandeau programme, liste des chaînes.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var poster: ImageView
    private lateinit var spinner: View
    private lateinit var flash: ImageView
    private lateinit var controls: View
    private lateinit var ctlTitle: TextView
    private lateinit var ctlSubtitle: TextView
    private lateinit var seek: SeekBar
    private lateinit var scrubTime: TextView
    private lateinit var remaining: TextView
    private lateinit var ctlPlay: ImageView
    private lateinit var ctlLabel: TextView
    private lateinit var ctlNext: View
    private lateinit var nextCard: View
    private lateinit var nextBtn: View
    private lateinit var nextCountdown: ProgressBar
    private lateinit var panel: View
    private lateinit var colA: LinearLayout
    private lateinit var colB: LinearLayout
    private lateinit var still: View
    private lateinit var osd: View
    private lateinit var chPanel: View
    private lateinit var chList: RecyclerView
    private lateinit var zapText: TextView
    private lateinit var status: View
    private lateinit var toastView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<PlayItem> = emptyList()
    private var index = 0
    private var live = false
    private var retries = 0
    private var failed = false
    private var digits = ""
    private var wifiLock: WifiManager.WifiLock? = null
    private var epgJob: Job? = null
    private val hhmm = SimpleDateFormat("HH:mm", Locale.FRANCE)

    // Films / séries
    private var pendingSeek: Long? = null
    private var nextShown = false
    private var nextDismissedFor: String? = null
    private var countdown: ValueAnimator? = null
    private var autoAdvances = 0
    private var lastKey: String? = null
    private var lastPos = 0L
    private var lastDur = 0L
    private var lastSave = 0L

    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideOsd = Runnable { osd.fade(false, 300) }
    private val applyDigits = Runnable { jumpToNumber() }
    private val applySeek = Runnable { commitSeek() }
    private val retryRunnable = Runnable {
        player?.let {
            if (live) it.setMediaItem(PlayerFactory.mediaItem(queue[index]))
            it.prepare(); it.play()
        }
    }
    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = Repo.queue
        if (queue.isEmpty()) { finish(); return }
        index = Repo.queueIndex.coerceIn(0, queue.size - 1)
        live = queue[index].isLive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        bindViews()

        playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, Fonts.semibold))
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * floatArrayOf(0.85f, 1.05f, 1.3f)[Prefs.subtitleSize.coerceIn(0, 2)])
        }

        if (!live) {
            Img.card(poster, queue[index].logo, 960, 540)
            setupControls()
        } else poster.show(false)

        @SuppressLint("WifiManagerLeak")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flux:playback")?.apply { setReferenceCounted(false) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    still.visibility == View.VISIBLE -> finish()
                    panel.visibility == View.VISIBLE -> closePanel()
                    chPanel.visibility == View.VISIBLE -> chPanel.fade(false)
                    nextShown -> { nextDismissedFor = player?.currentMediaItem?.mediaId; hideNext() }
                    controls.visibility == View.VISIBLE && !live -> hideControls()
                    else -> finish()
                }
            }
        })
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        poster = findViewById(R.id.poster)
        spinner = findViewById(R.id.spinner)
        flash = findViewById(R.id.flash)
        controls = findViewById(R.id.controls)
        ctlTitle = findViewById(R.id.ctlTitle)
        ctlSubtitle = findViewById(R.id.ctlSubtitle)
        seek = findViewById(R.id.seek)
        scrubTime = findViewById(R.id.scrubTime)
        remaining = findViewById(R.id.remaining)
        ctlPlay = findViewById(R.id.ctlPlay)
        ctlLabel = findViewById(R.id.ctlLabel)
        ctlNext = findViewById(R.id.ctlNext)
        nextCard = findViewById(R.id.nextCard)
        nextBtn = findViewById(R.id.nextBtn)
        nextCountdown = findViewById(R.id.nextCountdown)
        panel = findViewById(R.id.panel)
        colA = findViewById(R.id.panelColA)
        colB = findViewById(R.id.panelColB)
        still = findViewById(R.id.still)
        osd = findViewById(R.id.osd)
        chPanel = findViewById(R.id.chPanel)
        chList = findViewById(R.id.chList)
        zapText = findViewById(R.id.zapText)
        status = findViewById(R.id.status)
        toastView = findViewById(R.id.toast)
    }

    override fun onStart() {
        super.onStart()
        if (queue.isNotEmpty()) start()
    }

    override fun onPause() {
        // Avant le retour à la fiche : sa onResume() passe avant notre onStop()
        savePosition()
        super.onPause()
    }

    override fun onStop() {
        savePosition()
        release()
        super.onStop()
    }

    // ================================================================== lecture

    private fun start() {
        val p = PlayerFactory.create(this)
        player = p
        playerView.player = p
        p.addListener(listener)
        wifiLock?.acquire()        // Wi-Fi en mode haute performance pendant la lecture
        spinner.show(true)
        if (live) {
            playLive(index)
        } else {
            val startPos = if (Repo.resume) resumePosition(queue[index].key) else 0L
            p.setMediaItems(queue.map(PlayerFactory::mediaItem), index, startPos)
            p.prepare()
            p.playWhenReady = true
            if (startPos > 0) showToast("Reprise à ${Fmt.time(startPos)}")
            updateTitles()
            handler.post(ticker)
        }
    }

    private fun resumePosition(key: String): Long {
        val pos = Prefs.pos(key); val dur = Prefs.dur(key)
        return if (pos > 20_000 && (dur <= 0 || pos < dur - 60_000)) pos else 0L
    }

    private fun release() {
        handler.removeCallbacksAndMessages(null)
        countdown?.cancel()
        epgJob?.cancel()
        player?.removeListener(listener)
        player?.release()
        player = null
        if (::playerView.isInitialized) playerView.player = null
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun savePosition() {
        val p = player ?: return
        if (live) return
        val mi = p.currentMediaItem ?: return
        // Durée inconnue (vidéo pas encore chargée) : on ne touche pas à la reprise existante
        if (p.duration == C.TIME_UNSET || p.duration <= 0) return
        Prefs.savePos(mi.mediaId, p.currentPosition, p.duration)
        index = p.currentMediaItemIndex
        Repo.queueIndex = index
        Repo.resume = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            Log.i("FluxQA", "player state=$state live=$live item=${player?.currentMediaItem?.mediaId}")
            spinner.show(state == Player.STATE_BUFFERING)
            if (state == Player.STATE_READY) {
                retries = 0; failed = false
                status.show(false)
            }
            if (state == Player.STATE_ENDED) {
                if (live) retry(null)
                else if (player?.hasNextMediaItem() != true) { savePosition(); finish() }
            }
        }

        override fun onRenderedFirstFrame() {
            Log.i("FluxQA", "player firstFrame live=$live")
            poster.fade(false, 500)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (live) return
            ctlPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (controls.visibility == View.VISIBLE) scheduleHide()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("FluxQA", "player error ${error.errorCodeName}", error)
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.seekToDefaultPosition(); player?.prepare(); return
            }
            retry(error)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (live) return
            val p = player ?: return
            // L'épisode précédent : terminé (auto) ou position mémorisée
            lastKey?.let { k ->
                if (k != mediaItem?.mediaId) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && lastDur > 0) Prefs.savePos(k, lastDur, lastDur)
                    else if (lastDur > 0) Prefs.savePos(k, lastPos, lastDur)
                }
            }
            index = p.currentMediaItemIndex
            Repo.queueIndex = index
            nextShown = false; nextDismissedFor = null
            nextCard.show(false); countdown?.cancel()
            queue.getOrNull(index)?.episode?.let { ep -> Repo.seriesId?.let { Prefs.setLastEpisode(it, ep) } }
            updateTitles()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) registerAutoAdvance()
        }
    }

    /** Code HTTP renvoyé par le serveur de flux, s'il est à l'origine de l'erreur. */
    private fun httpCode(error: PlaybackException?): Int? =
        generateSequence(error?.cause) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    /**
     * Le serveur refuse la reprise : en IPTV, c'est presque toujours la connexion coupée qui
     * compte encore comme « écran utilisé » pendant 30 s à 1 min (abonnement 1 écran), ou un
     * serveur saturé. Réessayer tout de suite est alors refusé à chaque fois : on patiente.
     */
    private fun isServerRefusal(code: Int?) = code != null && (code in 401..403 || code == 429 || code == 458 || code >= 500)

    private fun retry(error: PlaybackException?) {
        handler.removeCallbacks(retryRunnable)
        val code = httpCode(error)
        if (code != null) Log.w("FluxQA", "player HTTP $code (essai $retries)")
        if (retries >= MAX_RETRIES) {
            failed = true
            spinner.show(false)
            showStatus("Lecture impossible", explainFailure(error, code) + "\n\nOK pour réessayer  ·  Retour pour quitter")
            return
        }
        retries++
        // Refus du serveur : 5 s, 10 s, 15 s, 20 s, 30 s, 30 s (≈ 2 min au total) ; sinon reprise rapide
        val delayMs = if (isServerRefusal(code)) longArrayOf(5, 10, 15, 20, 30, 30)[retries - 1] * 1000 else 700L * retries
        val why = code?.let { "Le serveur a répondu HTTP $it · " } ?: ""
        showStatus("Reconnexion…", "${why}tentative $retries sur $MAX_RETRIES dans ${(delayMs + 999) / 1000} s")
        handler.postDelayed(retryRunnable, delayMs)
    }

    private fun explainFailure(error: PlaybackException?, code: Int?): String = when {
        code == 401 || code == 403 || code == 458 -> "Le serveur refuse le flux (HTTP $code). Le plus souvent : nombre d'écrans " +
            "de l'abonnement atteint (autre appareil, ou ancienne connexion pas encore libérée). Patientez une minute puis réessayez."
        code == 404 || code == 410 -> "Ce contenu n'est plus disponible sur le serveur (HTTP $code)."
        code == 429 || code == 509 -> "Le serveur limite les connexions (HTTP $code). Patientez une minute puis réessayez."
        code != null && code >= 500 -> "Le serveur de votre fournisseur est en difficulté (HTTP $code). Réessayez dans quelques minutes."
        code != null -> "Le serveur a répondu HTTP $code."
        else -> error?.errorCodeName ?: "Erreur de lecture."
    }

    private fun showStatus(title: String, text: String) {
        findViewById<TextView>(R.id.statusTitle).text = title
        findViewById<TextView>(R.id.statusText).text = text
        status.fade(true)
    }

    private fun showToast(msg: String) {
        toastView.text = msg
        toastView.animate().cancel()
        toastView.alpha = 0f; toastView.translationY = (-10f).dpf
        toastView.animate().alpha(1f).translationY(0f).setDuration(250).withEndAction {
            toastView.animate().alpha(0f).setStartDelay(2200).setDuration(400).start()
        }.start()
    }

    // ================================================================== contrôles (films / séries)

    private fun setupControls() {
        val buttons = listOf<View>(ctlPlay, findViewById(R.id.ctlRew), findViewById(R.id.ctlFwd),
            findViewById(R.id.ctlTracks), findViewById(R.id.ctlSpeed), ctlNext)
        buttons.forEach { b ->
            b.setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(120).start()
                if (has) ctlLabel.text = v.contentDescription
            }
        }
        seek.setOnFocusChangeListener { _, has -> if (has) ctlLabel.text = "◀ ▶  avancer / reculer" }
        ctlPlay.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.ctlRew).setOnClickListener { jump(-10_000) }
        findViewById<View>(R.id.ctlFwd).setOnClickListener { jump(10_000) }
        findViewById<View>(R.id.ctlTracks).setOnClickListener { openTracks() }
        findViewById<View>(R.id.ctlSpeed).setOnClickListener { openSpeed() }
        ctlNext.setOnClickListener { goNext(auto = false) }
        nextBtn.setOnClickListener { goNext(auto = false) }
        findViewById<View>(R.id.stillContinue).setOnClickListener {
            still.fade(false); autoAdvances = 0
            player?.play()
        }
        findViewById<View>(R.id.stillQuit).setOnClickListener { finish() }
    }

    private fun updateTitles() {
        val q = queue.getOrNull(player?.currentMediaItemIndex ?: index) ?: return
        ctlTitle.text = q.title
        ctlSubtitle.text = q.subtitle ?: ""
        ctlSubtitle.show(!q.subtitle.isNullOrEmpty())
        ctlNext.show(player?.hasNextMediaItem() == true)
    }

    private fun showControls(focus: View? = null) {
        if (live) return
        if (controls.visibility != View.VISIBLE) {
            updateTitles()
            updateSeekUi(pendingSeek)
            controls.fade(true, 200)
            subtitlesAbove(true)
        }
        val target = focus ?: currentFocus?.takeIf { isInside(it, controls) } ?: ctlPlay
        target.requestFocus()
        scheduleHide()
    }

    private fun hideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (pendingSeek != null) commitSeek()
        controls.fade(false, 250)
        subtitlesAbove(false)
    }

    /** Comme sur Netflix : les sous-titres remontent au-dessus de la barre de lecture quand elle est affichée. */
    private fun subtitlesAbove(controlsShown: Boolean) {
        playerView.subtitleView?.setBottomPaddingFraction(if (controlsShown) 0.30f else 0.08f)
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true) handler.postDelayed(hideControlsRunnable, 5_000)
    }

    private fun togglePlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        flashIcon(if (p.playWhenReady) R.drawable.ic_play else R.drawable.ic_pause)
    }

    private fun flashIcon(res: Int) {
        flash.setImageResource(res)
        flash.animate().cancel()
        flash.alpha = 1f; flash.scaleX = 0.8f; flash.scaleY = 0.8f
        flash.animate().scaleX(1.15f).scaleY(1.15f).alpha(0f).setDuration(600).start()
    }

    private fun jump(delta: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + delta).coerceIn(0, duration()))
        flashIcon(if (delta < 0) R.drawable.ic_rewind else R.drawable.ic_forward)
        updateSeekUi(null)
    }

    private fun duration(): Long = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE

    /** Avance/retour rapide : plus on maintient, plus ça va vite. */
    private fun scrub(dir: Int, repeat: Int) {
        val p = player ?: return
        val step = when { repeat > 20 -> 60_000L; repeat > 6 -> 30_000L; else -> 10_000L }
        val base = pendingSeek ?: p.currentPosition
        val target = (base + dir * step).coerceIn(0, duration())
        pendingSeek = target
        updateSeekUi(target)
        handler.removeCallbacks(applySeek)
        handler.postDelayed(applySeek, 700)
    }

    private fun commitSeek() {
        handler.removeCallbacks(applySeek)
        pendingSeek?.let { player?.seekTo(it) }
        pendingSeek = null
        scrubTime.visibility = View.INVISIBLE
        scheduleHide()
    }

    private fun updateSeekUi(preview: Long?) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val pos = preview ?: p.currentPosition
        seek.max = (dur / 1000).toInt()
        seek.progress = (pos / 1000).toInt()
        seek.secondaryProgress = (p.bufferedPosition / 1000).toInt()
        remaining.text = "-" + Fmt.time(dur - pos)
        if (preview != null) {
            scrubTime.text = Fmt.time(preview)
            scrubTime.visibility = View.VISIBLE
            val usable = seek.width - seek.paddingLeft - seek.paddingRight
            val x = seek.left + seek.paddingLeft + usable * (preview.toFloat() / dur) - scrubTime.width / 2f
            scrubTime.translationX = x.coerceAtLeast(0f)
        }
    }

    private fun tick() {
        val p = player ?: return
        val mi = p.currentMediaItem ?: return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        lastKey = mi.mediaId; lastPos = p.currentPosition; lastDur = dur
        if (controls.visibility == View.VISIBLE && pendingSeek == null) updateSeekUi(null)

        // Sauvegarde régulière (coupure de courant, retour accueil…)
        val now = System.currentTimeMillis()
        if (now - lastSave > 15_000 && p.isPlaying) { lastSave = now; Prefs.savePos(mi.mediaId, lastPos, dur) }

        // Carte "Épisode suivant" pendant le générique
        val left = dur - p.currentPosition
        if (!nextShown && Repo.seriesId != null && p.hasNextMediaItem() && dur > 5 * 60_000 &&
            left in 1..25_000 && nextDismissedFor != mi.mediaId && p.isPlaying && still.visibility != View.VISIBLE
        ) showNext(left)
    }

    private fun showNext(left: Long) {
        val next = queue.getOrNull(index + 1) ?: return
        nextShown = true
        hideControls()
        findViewById<TextView>(R.id.nextTitle).text = next.subtitle ?: next.title
        Img.card(findViewById(R.id.nextImage), next.logo, 288, 162)
        nextCard.translationY = 30f.dpf
        nextCard.fade(true)
        nextCard.animate().translationY(0f).setDuration(300).start()
        nextBtn.requestFocus()
        countdown?.cancel()
        nextCountdown.progress = 0
        if (Prefs.autoplayNext) {
            countdown = ValueAnimator.ofInt(0, 1000).apply {
                duration = (left - 800).coerceIn(3_000, 10_000)
                interpolator = LinearInterpolator()
                addUpdateListener { nextCountdown.progress = it.animatedValue as Int }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) goNext(auto = true) }
                })
                start()
            }
        }
    }

    private fun hideNext() {
        countdown?.cancel()
        nextShown = false
        nextCard.fade(false)
    }

    private fun goNext(auto: Boolean) {
        val p = player ?: return
        if (!p.hasNextMediaItem()) return
        // On note l'épisode en cours comme vu
        p.currentMediaItem?.mediaId?.let { k -> if (lastDur > 0) Prefs.savePos(k, lastDur, lastDur) }
        lastKey = null
        hideNext()
        p.seekToNextMediaItem()
        p.play()
        if (auto) registerAutoAdvance()
    }

    /** Après 3 épisodes enchaînés sans toucher la télécommande : "Vous regardez toujours ?" */
    private fun registerAutoAdvance() {
        autoAdvances++
        if (autoAdvances >= 3) {
            player?.pause()
            findViewById<TextView>(R.id.stillText).text = queue.getOrNull(index)?.let { "${it.title}  ·  ${it.subtitle ?: ""}" } ?: ""
            still.fade(true)
            findViewById<View>(R.id.stillContinue).requestFocus()
        }
    }

    // ------------------------------------------------------------------ audio / sous-titres / vitesse

    private fun openTracks() {
        val p = player ?: return
        hideControls()
        colA.removeAllViews(); colB.removeAllViews()
        header(colA, "Audio")
        header(colB, "Sous-titres")
        var focus: View? = null
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val v = option(colA, trackName(f, n, audio = true), g.isTrackSelected(i)) {
                    selectTrack(g, i); f.language?.let { Prefs.audioLang = it }
                }
                if (g.isTrackSelected(i)) focus = v
            }
        }
        if (n == 0) hintText(colA, "Piste unique")
        val anySub = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val off = option(colB, "Désactivés", !anySub) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            Prefs.subLang = ""
        }
        n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                option(colB, trackName(f, n, audio = false), g.isTrackSelected(i)) {
                    selectTrack(g, i); f.language?.let { Prefs.subLang = it }
                }
            }
        }
        if (n == 0) hintText(colB, "Aucun sous-titre dans ce flux")
        openPanel(focus ?: colA.getChildAt(1) ?: off)
    }

    private fun openSpeed() {
        val p = player ?: return
        hideControls()
        colA.removeAllViews(); colB.removeAllViews()
        header(colA, "Vitesse de lecture")
        var focus: View? = null
        for ((label, speed) in listOf("0,5×" to 0.5f, "0,75×" to 0.75f, "1× (normale)" to 1f, "1,25×" to 1.25f, "1,5×" to 1.5f)) {
            val sel = kotlin.math.abs(p.playbackParameters.speed - speed) < 0.01f
            val v = option(colA, label, sel) { p.setPlaybackSpeed(speed) }
            if (sel) focus = v
        }
        openPanel(focus ?: colA.getChildAt(1))
    }

    private fun selectTrack(g: Tracks.Group, i: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(g.type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            .build()
    }

    private fun trackName(f: Format, n: Int, audio: Boolean): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            @Suppress("DEPRECATION")
            Locale(code).getDisplayLanguage(Locale.FRENCH).replaceFirstChar { it.uppercase() }
        }
        val base = lang ?: f.label ?: (if (audio) "Piste $n" else "Sous-titres $n")
        val extra = buildList {
            if (f.label != null && lang != null && f.label != lang) add(f.label!!)
            if (audio) when (f.channelCount) { 6 -> add("5.1"); 8 -> add("7.1") }
            if (!audio && (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) add("forcés")
        }
        return if (extra.isEmpty()) base else "$base (${extra.joinToString(", ")})"
    }

    private fun header(col: LinearLayout, text: String) {
        col.addView(TextView(this).apply {
            this.text = text; typeface = Fonts.bold; textSize = 18f; setTextColor(0xFFF5F5F1.toInt())
            setPadding(14.dp, 0, 0, 14.dp)
        })
    }

    private fun hintText(col: LinearLayout, text: String) {
        col.addView(TextView(this).apply {
            this.text = text; typeface = Fonts.regular; textSize = 14f; setTextColor(0xFF7A7A7A.toInt())
            setPadding(14.dp, 6.dp, 0, 0)
        })
    }

    private fun option(col: LinearLayout, label: String, selected: Boolean, action: () -> Unit): View {
        val v = TextView(this).apply {
            text = (if (selected) "✓   " else "      ") + label
            typeface = if (selected) Fonts.bold else Fonts.medium
            textSize = 15f
            setTextColor(ContextCompat.getColorStateList(context, R.color.list_fg))
            background = ContextCompat.getDrawable(context, R.drawable.list_item_bg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 14.dp, 0)
            isSingleLine = true
            isFocusable = true; isClickable = true
            isSelected = selected
            setOnClickListener { action(); closePanel() }
        }
        col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp).apply { bottomMargin = 3.dp })
        return v
    }

    private fun openPanel(focus: View?) {
        panel.translationX = 40f.dpf
        panel.fade(true)
        panel.animate().translationX(0f).setDuration(260).start()
        focus?.requestFocus()
    }

    private fun closePanel() {
        panel.fade(false)
        showControls(findViewById(R.id.ctlTracks))
    }

    // ================================================================== direct

    private fun playLive(i: Int) {
        index = ((i % queue.size) + queue.size) % queue.size
        Repo.queueIndex = index
        val item = queue[index]
        Prefs.lastChannel = item.key
        Prefs.pushRecentChannel(item.key)
        retries = 0; failed = false
        handler.removeCallbacks(retryRunnable)
        status.show(false)
        spinner.show(true)
        player?.apply {
            setMediaItem(PlayerFactory.mediaItem(item))
            prepare()
            play()
        }
        showOsd(item)
    }

    private fun showOsd(item: PlayItem = queue[index]) {
        findViewById<TextView>(R.id.osdNum).text = if (item.num > 0) "Chaîne ${item.num}" else ""
        findViewById<TextView>(R.id.osdName).text = item.title
        Img.card(findViewById(R.id.osdLogo), item.logo, 272, 152)
        val now = findViewById<TextView>(R.id.osdNow)
        val next = findViewById<TextView>(R.id.osdNext)
        val prog = findViewById<ProgressBar>(R.id.osdProgress)
        now.text = ""; next.text = ""; prog.visibility = View.INVISIBLE
        osd.fade(true, 180)
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 5000)

        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            val t = System.currentTimeMillis() / 1000
            val list = runCatching { Repo.api().shortEpg(item.streamId) }.getOrNull().orEmpty().filter { it.end > t }
            if (queue[index].key != item.key) return@launch
            list.getOrNull(0)?.let { e ->
                now.text = "${hhmm.format(Date(e.start * 1000))} – ${hhmm.format(Date(e.end * 1000))}    ${e.title}"
                if (e.end > e.start) {
                    prog.progress = (((t - e.start).toFloat() / (e.end - e.start)) * 1000).toInt().coerceIn(0, 1000)
                    prog.visibility = View.VISIBLE
                }
            }
            list.getOrNull(1)?.let { e -> next.text = "À suivre  ${hhmm.format(Date(e.start * 1000))}   ${e.title}" }
        }
    }

    private fun toggleOsd() {
        if (osd.visibility == View.VISIBLE) { handler.removeCallbacks(hideOsd); osd.fade(false) } else showOsd()
    }

    private fun jumpToNumber() {
        val n = digits.toIntOrNull()
        digits = ""
        zapText.fade(false)
        if (n == null) return
        val target = queue.indexOfFirst { it.num == n }.takeIf { it >= 0 } ?: (n - 1).takeIf { it in queue.indices }
        if (target != null) playLive(target) else showToast("Chaîne $n introuvable dans cette liste")
    }

    private fun openChannelPanel() {
        osd.fade(false)
        if (chList.adapter == null) {
            chList.layoutManager = LinearLayoutManager(this)
            chList.adapter = ChannelAdapter()
            chList.itemAnimator = null
        } else chList.adapter?.notifyDataSetChanged()
        chPanel.fade(true)
        chList.scrollToPosition(index)
        chList.post {
            (chList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, chList.height / 3)
            chList.post { chList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.chNum)
            val logo: ImageView = v.findViewById(R.id.chLogo)
            val title: TextView = v.findViewById(R.id.chTitle)
        }

        override fun getItemCount() = queue.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            val vh = VH(v)
            v.setOnClickListener {
                val p = vh.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) { chPanel.fade(false); playLive(p) }
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = queue[position]
            h.num.text = if (c.num > 0) c.num.toString() else ""
            h.title.text = c.title
            h.itemView.isSelected = position == index
            Img.card(h.logo, c.logo, 136, 80)
        }
    }

    // ================================================================== télécommande

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            autoAdvances = if (still.visibility == View.VISIBLE) autoAdvances else 0
            val handled = if (live) handleLiveKey(event.keyCode) else handleVodKey(event.keyCode, event.repeatCount)
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleLiveKey(code: Int): Boolean {
        if (chPanel.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) { chPanel.fade(false); return true }
            return false
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { playLive(index + 1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { playLive(index - 1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (failed) { retries = 0; failed = false; retry(null) } else toggleOsd(); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> { openChannelPanel(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { showOsd(); true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_0)
                zapText.text = digits
                zapText.fade(true, 120)
                handler.removeCallbacks(applyDigits)
                handler.postDelayed(applyDigits, 1500)
                true
            }
            else -> false
        }
    }

    private fun handleVodKey(code: Int, repeat: Int): Boolean {
        val p = player ?: return false
        if (still.visibility == View.VISIBLE || panel.visibility == View.VISIBLE) return false
        if (failed && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            retries = 0; failed = false; retry(null); return true
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!p.playWhenReady) togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (p.playWhenReady) togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { showControls(seek); scrub(1, repeat); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { showControls(seek); scrub(-1, repeat); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { goNext(auto = false); return true }
        }
        if (nextShown && controls.visibility != View.VISIBLE) return false   // le bouton "Épisode suivant" a le focus

        if (controls.visibility != View.VISIBLE) {
            return when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePlay(); showControls(ctlPlay); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { showControls(seek); scrub(-1, repeat); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(seek); scrub(1, repeat); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { showControls(); true }
                else -> false
            }
        }
        scheduleHide()
        if (currentFocus === seek) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { scrub(-1, repeat); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(1, repeat); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (pendingSeek != null) commitSeek() else togglePlay(); return true }
            }
        }
        if (pendingSeek != null && code == KeyEvent.KEYCODE_DPAD_DOWN) commitSeek()
        return false
    }

    private fun isInside(v: View, container: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === container) return true
            p = p.parent as? View
        }
        return false
    }

    companion object {
        private const val MAX_RETRIES = 6
    }
}
