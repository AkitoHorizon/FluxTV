package com.flux.tv.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.flux.tv.R
import com.flux.tv.data.CardStyle
import com.flux.tv.data.Item
import com.flux.tv.data.Kind
import com.flux.tv.data.Prefs
import com.flux.tv.data.Repo
import com.flux.tv.data.Row
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Accueil façon Netflix :
 *  - menu latéral en rail d'icônes qui s'ouvre au focus,
 *  - "billboard" à la une qui défile tout seul (Lecture / Plus d'infos),
 *  - rangées Reprendre, Top 10, Ma liste, Parce que vous avez regardé…, catégories,
 *  - fond teinté par la couleur dominante de l'affiche sélectionnée.
 */
class MainActivity : BaseActivity(), CardListener {

    private enum class Section(val label: String, val icon: Int) {
        SEARCH("Recherche", R.drawable.ic_search),
        HOME("Accueil", R.drawable.ic_home),
        SERIES("Séries", R.drawable.ic_series),
        MOVIES("Films", R.drawable.ic_movie),
        NEW("Nouveautés", R.drawable.ic_new),
        LIVE("TV en direct", R.drawable.ic_live),
        LIST("Ma liste", R.drawable.ic_list),
    }

    private lateinit var root: View
    private lateinit var heroImage: ImageView
    private lateinit var heroInfo: View
    private lateinit var heroKicker: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroPlot: TextView
    private lateinit var heroButtons: View
    private lateinit var btnPlay: View
    private lateinit var btnPlayText: TextView
    private lateinit var btnInfo: View
    private lateinit var dots: LinearLayout
    private lateinit var rowsView: RecyclerView
    private lateinit var skeleton: View
    private lateinit var emptyBox: View
    private lateinit var nav: LinearLayout
    private lateinit var navScrim: View
    private lateinit var busy: View
    private lateinit var ambient: Ambient

    private val adapter = RowsAdapter(this)
    private val navViews = LinkedHashMap<Section, View>()
    private val navLabels = ArrayList<View>()
    private var profileNav: View? = null
    private var section = Section.HOME
    private var navOpen = false
    private var navAnim: ValueAnimator? = null
    private var lastContentFocus: View? = null

    private var featured: List<Item> = emptyList()
    private var featuredIndex = 0
    private var billboard = false
    private var shownKey: String? = null
    private var heroUrl: String? = null

    private var loadJob: Job? = null
    private var heroJob: Job? = null
    private var rotateJob: Job? = null
    private var loadedGen = -1
    private var loadedProfile = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.hasAccount) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish(); return
        }
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        heroImage = findViewById(R.id.heroImage)
        heroInfo = findViewById(R.id.heroInfo)
        heroKicker = findViewById(R.id.heroKicker)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroPlot = findViewById(R.id.heroPlot)
        heroButtons = findViewById(R.id.heroButtons)
        btnPlay = findViewById(R.id.btnPlay)
        btnPlayText = findViewById(R.id.btnPlayText)
        btnInfo = findViewById(R.id.btnInfo)
        dots = findViewById(R.id.dots)
        rowsView = findViewById(R.id.rows)
        skeleton = findViewById(R.id.skeleton)
        emptyBox = findViewById(R.id.emptyBox)
        nav = findViewById(R.id.nav)
        navScrim = findViewById(R.id.navScrim)
        busy = findViewById(R.id.busy)

        // Géométrie du visuel
        val h = resources.displayMetrics.heightPixels
        for (id in intArrayOf(R.id.heroImage, R.id.heroVignette, R.id.fadeLeft)) findViewById<View>(id).layoutParams.height = (h * 0.8f).toInt()
        (findViewById<View>(R.id.fadeBottom).layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = (h * 0.36f).toInt(); height = h - topMargin     // couvre aussi le débord du zoom Ken Burns
        }
        // En-tête : ~55 % de l'écran mais borné, pour garder 1,5 rangée visible sur toutes les densités
        findViewById<View>(R.id.header).layoutParams.height = (h * 0.55f).toInt().coerceIn(270.dp, 330.dp)
        ambient = Ambient(root, findViewById(R.id.fadeLeft), findViewById(R.id.fadeBottom))

        rowsView.layoutManager = FocusAlignLayoutManager(this, RecyclerView.VERTICAL)
        rowsView.adapter = adapter
        rowsView.setHasFixedSize(true)
        rowsView.itemAnimator = null
        rowsView.setItemViewCacheSize(2)

        buildNav()
        btnPlay.setOnClickListener { featured.getOrNull(featuredIndex)?.let { playItem(it) } }
        btnInfo.setOnClickListener { featured.getOrNull(featuredIndex)?.let { openDetails(it) } }
        findViewById<View>(R.id.retry).setOnClickListener { show(section, focusContent = true) }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> if (now != null) onFocusMoved(now) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val f = currentFocus
                when {
                    busy.visibility == View.VISIBLE -> {}
                    f != null && isInside(f, nav) -> if (section != Section.HOME) show(Section.HOME, true) else focusContent()
                    !billboard && featured.isNotEmpty() -> { rowsView.scrollToPosition(0); enterBillboard(focus = true) }
                    else -> focusNav()
                }
            }
        })

        // Préchargement des catalogues pendant l'écran "Qui regarde ?"
        lifecycleScope.launch { runCatching { Repo.load(Kind.MOVIE) } }
        lifecycleScope.launch { runCatching { Repo.load(Kind.SERIES) } }
        if (!Session.profileChosen) startActivity(Intent(this, ProfilesActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (!Session.profileChosen) return
        updateNavProfile()
        if (loadedProfile != Prefs.profileId || loadedGen != Repo.generation) show(Section.HOME, focusContent = true)
        else {
            refreshPersonalRows()
            if (billboard) startRotation()
        }
    }

    override fun onPause() {
        rotateJob?.cancel()
        super.onPause()
    }

    // ================================================================== menu latéral

    private fun buildNav() {
        val items = findViewById<LinearLayout>(R.id.navItems)
        val bottom = findViewById<LinearLayout>(R.id.navBottom)
        profileNav = navItem(items, null, "Profil") {
            startActivity(Intent(this, ProfilesActivity::class.java).putExtra(ProfilesActivity.EXTRA_SWITCH, true))
        }.also { (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = 18.dp }
        Section.values().forEach { s ->
            navViews[s] = navItem(items, ContextCompat.getDrawable(this, s.icon), s.label) {
                when (s) {
                    Section.SEARCH -> startActivity(Intent(this, SearchActivity::class.java))
                    Section.LIVE -> startActivity(Intent(this, LiveActivity::class.java))
                    else -> show(s, focusContent = true)
                }
            }
        }
        navItem(bottom, ContextCompat.getDrawable(this, R.drawable.ic_settings), "Paramètres") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun navItem(parent: ViewGroup, icon: Drawable?, label: String, onClick: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_nav, parent, false)
        icon?.let { v.findViewById<ImageView>(R.id.navIcon).setImageDrawable(it) }
        val lbl = v.findViewById<TextView>(R.id.navLabel)
        lbl.text = label
        navLabels += lbl
        v.setOnClickListener { onClick() }
        parent.addView(v)
        return v
    }

    private fun updateNavProfile() {
        val p = Prefs.profile ?: return
        val v = profileNav ?: return
        val icon = v.findViewById<ImageView>(R.id.navIcon)
        icon.imageTintList = null
        icon.setImageDrawable(avatarDrawable(this, p.color))
        v.findViewById<TextView>(R.id.navLabel).text = p.name
    }

    private fun openNav(open: Boolean) {
        if (navOpen == open) return
        navOpen = open
        navAnim?.cancel()
        val from = nav.layoutParams.width
        val to = if (open) resources.getDimensionPixelSize(R.dimen.nav_expanded) else resources.getDimensionPixelSize(R.dimen.nav_collapsed)
        navAnim = ValueAnimator.ofInt(from, to).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { nav.layoutParams.width = it.animatedValue as Int; nav.requestLayout() }
            start()
        }
        navScrim.animate().alpha(if (open) 1f else 0f).setDuration(240).start()
        navLabels.forEach { it.animate().alpha(if (open) 1f else 0f).setDuration(if (open) 260 else 120).start() }
        if (open) rotateJob?.cancel() else if (billboard) startRotation()
    }

    private fun focusNav() {
        (navViews[section] ?: navViews[Section.HOME])?.requestFocus()
    }

    private fun focusContent() {
        val last = lastContentFocus
        if (last != null && last.isAttachedToWindow && last.isShown) { last.requestFocus(); return }
        if (billboard && heroButtons.visibility == View.VISIBLE) btnPlay.requestFocus() else focusFirstCard()
    }

    private fun onFocusMoved(v: View) {
        if (isInside(v, nav)) { openNav(true); return }
        openNav(false)
        if (v === btnPlay || v === btnInfo) {
            lastContentFocus = v
            if (!billboard) enterBillboard(focus = false)
        } else if (isInside(v, rowsView)) {
            lastContentFocus = v
            if (billboard) exitBillboard()
        }
    }

    // ================================================================== télécommande

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (f != null && (isFirstCard(f) || f === btnPlay || isInside(f, emptyBox))) { focusNav(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (f != null && isInside(f, nav)) { focusContent(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (f != null && featured.isNotEmpty() && rowIndexOf(f) == 0) { enterBillboard(focus = true); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (f === btnPlay || f === btnInfo) { focusFirstCard(); return true }
                KeyEvent.KEYCODE_MENU -> cardOf(f)?.let { (item, row) -> onCardLongPressed(item, row); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(v: View, container: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === container) return true
            p = p.parent as? View
        }
        return false
    }

    private fun isFirstCard(f: View): Boolean {
        val rv = f.parent as? RecyclerView ?: return false
        return rv.id == R.id.rowList && rv.getChildAdapterPosition(f) == 0
    }

    private fun rowIndexOf(f: View): Int {
        val rv = f.parent as? RecyclerView ?: return -1
        if (rv.id != R.id.rowList) return -1
        val rowView = rv.parent as? View ?: return -1
        return rowsView.getChildAdapterPosition(rowView)
    }

    private fun cardOf(f: View?): Pair<Item, Row?>? {
        val v = f ?: return null
        val rv = v.parent as? RecyclerView ?: return null
        val vh = rv.getChildViewHolder(v) as? CardAdapter.VH ?: return null
        val item = vh.item ?: return null
        return item to adapter.rows.getOrNull(rowIndexOf(v))
    }

    private fun focusFirstCard(attempt: Int = 0) {
        val row = rowsView.findViewHolderForAdapterPosition(0) as? RowsAdapter.VH
        val card = row?.list?.getChildAt(0)
        if (card != null) card.requestFocus()
        else if (attempt < 20) rowsView.postDelayed({ focusFirstCard(attempt + 1) }, 50)
    }

    // ================================================================== sections

    private fun show(s: Section, focusContent: Boolean) {
        section = s
        navViews.forEach { (k, v) -> v.isSelected = k == s }
        loadJob?.cancel(); heroJob?.cancel(); rotateJob?.cancel()
        billboard = false
        heroButtons.visibility = View.INVISIBLE
        emptyBox.show(false)
        skeleton.show(true)
        adapter.submit(emptyList())
        lastContentFocus = null
        loadJob = lifecycleScope.launch {
            try {
                val gen = Repo.generation
                val prof = Prefs.profileId
                val (rows, feat) = when (s) {
                    Section.HOME -> homeRows()
                    Section.MOVIES -> kindRows(Kind.MOVIE)
                    Section.SERIES -> kindRows(Kind.SERIES)
                    Section.NEW -> newRows()
                    else -> myListRows() to emptyList()
                }
                loadedGen = gen; loadedProfile = prof
                Log.i("FluxQA", "section=$s rows=${rows.size} featured=${feat.size}")
                skeleton.show(false)
                adapter.submit(rows, rowsView)
                rowsView.scrollToPosition(0)
                featured = feat
                featuredIndex = 0
                buildDots()
                when {
                    rows.isEmpty() && s == Section.LIST -> showEmpty("Votre liste est vide",
                        "Ajoutez des films, séries et chaînes avec le bouton « Ma liste », ou par un appui long sur OK.", retry = false)
                    rows.isEmpty() -> showEmpty("Rien à afficher pour l'instant", "Votre fournisseur ne propose pas de contenu dans cette section.", retry = true)
                    featured.isNotEmpty() -> enterBillboard(focus = focusContent)
                    else -> {
                        rows.first().items.firstOrNull()?.let { showItem(it) }
                        if (focusContent) focusFirstCard()
                    }
                }
                prefetchFeatured()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("FluxQA", "section load failed", e)
                skeleton.show(false)
                showEmpty("Impossible de charger le catalogue", e.message ?: e.javaClass.simpleName, retry = true)
            }
        }
    }

    private fun showEmpty(title: String, text: String, retry: Boolean) {
        findViewById<TextView>(R.id.emptyTitle).text = title
        findViewById<TextView>(R.id.emptyText).text = text
        val r = findViewById<View>(R.id.retry)
        r.show(retry)
        emptyBox.fade(true)
        if (retry) r.requestFocus() else focusNav()
    }

    // ------------------------------------------------------------------ rangées

    private suspend fun homeRows(): Pair<List<Row>, List<Item>> = coroutineScope {
        val m = async { runCatching { Repo.load(Kind.MOVIE) } }
        val s = async { runCatching { Repo.load(Kind.SERIES) } }
        val wantLive = Prefs.favorites().any { it.startsWith("l:") }
        val l = async { if (wantLive) runCatching { Repo.load(Kind.LIVE) } else Result.success(emptyList()) }
        val mr = m.await(); val sr = s.await(); l.await()
        if (mr.isFailure && sr.isFailure) throw mr.exceptionOrNull()!!
        val movies = mr.getOrDefault(emptyList())
        val series = sr.getOrDefault(emptyList())
        val mCats = Repo.rowsOf(Kind.MOVIE, movies).take(10)
        val sCats = Repo.rowsOf(Kind.SERIES, series).take(8)

        withContext(Dispatchers.Default) {
            val rows = ArrayList<Row>()
            continueRow(null)?.let(rows::add)
            if (movies.isNotEmpty()) rows += Row("Top 10 des films du moment", Repo.top10(Kind.MOVIE), CardStyle.TOP10, "top10m")
            listRow()?.let(rows::add)
            becauseRow()?.let(rows::add)
            if (series.isNotEmpty()) rows += Row("Top 10 des séries du moment", Repo.top10(Kind.SERIES), CardStyle.TOP10, "top10s")
            if (movies.isNotEmpty()) rows += Row("Nouveaux films", Repo.latest(Kind.MOVIE, 40), id = "newm")
            if (series.isNotEmpty()) rows += Row("Nouvelles séries", Repo.latest(Kind.SERIES, 40), id = "news")
            val favs = Prefs.favorites()
            Repo.list(Kind.LIVE).filter { it.key in favs }.takeIf { it.isNotEmpty() }
                ?.let { rows += Row("Mes chaînes", it, CardStyle.LOGO, "favlive") }
            for (i in 0 until maxOf(mCats.size, sCats.size)) {
                mCats.getOrNull(i)?.let(rows::add)
                sCats.getOrNull(i)?.let(rows::add)
            }
            rows to featuredFrom(movies + series)
        }
    }

    private suspend fun kindRows(kind: Kind): Pair<List<Row>, List<Item>> {
        val list = Repo.load(kind)
        val cats = Repo.rowsOf(kind, list)
        val word = if (kind == Kind.MOVIE) "films" else "séries"
        return withContext(Dispatchers.Default) {
            val rows = ArrayList<Row>()
            continueRow(kind)?.let(rows::add)
            rows += Row("Top 10 des $word du moment", Repo.top10(kind), CardStyle.TOP10, "top10")
            rows += Row(if (kind == Kind.MOVIE) "Nouveaux films" else "Nouvelles séries", Repo.latest(kind, 40), id = "latest")
            list.filter { it.ratingValue > 0 }.sortedByDescending { it.ratingValue }.take(40)
                .takeIf { it.size >= 6 }?.let { rows += Row("Les mieux notés", it, id = "best") }
            rows += cats
            rows to featuredFrom(list)
        }
    }

    private suspend fun newRows(): Pair<List<Row>, List<Item>> {
        val movies = runCatching { Repo.load(Kind.MOVIE) }.getOrDefault(emptyList())
        val series = runCatching { Repo.load(Kind.SERIES) }.getOrDefault(emptyList())
        return withContext(Dispatchers.Default) {
            val week = System.currentTimeMillis() / 1000 - 7L * 86400
            val rows = ArrayList<Row>()
            (movies + series).filter { it.added > week }.sortedByDescending { it.added }.take(60)
                .takeIf { it.isNotEmpty() }?.let { rows += Row("Ajoutés cette semaine", it, id = "week") }
            if (movies.isNotEmpty()) rows += Row("Nouveaux films", Repo.latest(Kind.MOVIE, 60), id = "newm")
            if (series.isNotEmpty()) rows += Row("Nouvelles séries", Repo.latest(Kind.SERIES, 60), id = "news")
            if (movies.isNotEmpty()) rows += Row("Top 10 des films du moment", Repo.top10(Kind.MOVIE), CardStyle.TOP10, "top10m")
            if (series.isNotEmpty()) rows += Row("Top 10 des séries du moment", Repo.top10(Kind.SERIES), CardStyle.TOP10, "top10s")
            rows to featuredFrom(Repo.latest(Kind.MOVIE, 30) + Repo.latest(Kind.SERIES, 30))
        }
    }

    private suspend fun myListRows(): List<Row> {
        val favs = Prefs.favorites()
        val rows = ArrayList<Row>()
        for ((kind, title) in listOf(Kind.MOVIE to "Films", Kind.SERIES to "Séries", Kind.LIVE to "Chaînes")) {
            if (favs.none { it.startsWith("${kind.prefix}:") }) continue
            val list = runCatching { Repo.load(kind) }.getOrDefault(emptyList()).filter { it.key in favs }
            if (list.isNotEmpty()) rows += Row(title, list, if (kind == Kind.LIVE) CardStyle.LOGO else CardStyle.POSTER, "fav$kind")
        }
        return rows
    }

    /** "Reprendre la lecture" : films commencés et séries en cours, du plus récent au plus ancien. */
    private fun continueRow(kind: Kind?): Row? {
        val list = Prefs.recents().mapNotNull { Repo.find(it) }
            .filter { kind == null || it.kind == kind }
            .filter {
                if (it.kind == Kind.SERIES) Prefs.episodeMeta(it.id) != null
                else Prefs.progress(it.key) in 0.01f..0.95f
            }.take(20)
        val who = Prefs.profile?.name
        return if (list.isEmpty()) null
        else Row(if (who != null) "Reprendre la lecture pour $who" else "Reprendre la lecture", list, CardStyle.WIDE, CONTINUE)
    }

    private fun listRow(): Row? {
        val favs = Prefs.favorites()
        val list = (Repo.list(Kind.MOVIE) + Repo.list(Kind.SERIES)).filter { it.key in favs }
        return if (list.isEmpty()) null else Row("Ma liste", list, id = MYLIST)
    }

    private fun becauseRow(): Row? {
        val seed = Prefs.recents().mapNotNull { Repo.find(it) }.firstOrNull { it.kind != Kind.LIVE } ?: return null
        val sim = Repo.similar(seed, 30)
        return if (sim.size < 5) null else Row("Parce que vous avez regardé ${seed.name}", sim, id = "because")
    }

    /** Titres à la une : récents, bien notés, avec un visuel. */
    private fun featuredFrom(list: List<Item>): List<Item> =
        list.filter { it.icon != null || it.backdrop != null }
            .sortedByDescending { it.added }.take(150)
            .sortedByDescending { it.ratingValue + (if (it.backdrop != null) 3 else 0) + (if (it.plot != null) 1 else 0) }
            .take(5)

    /** Charge en avance résumés et visuels larges des titres à la une. */
    private fun prefetchFeatured() {
        featured.forEach { item ->
            if (Repo.cachedDetails(item) != null) return@forEach
            lifecycleScope.launch {
                runCatching { Repo.details(item) }
                if (billboard && featured.getOrNull(featuredIndex)?.key == item.key) showItem(item, force = true)
            }
        }
    }

    private fun refreshPersonalRows() {
        if (section != Section.HOME && section != Section.MOVIES && section != Section.SERIES && section != Section.LIST) return
        if (section == Section.LIST) { show(Section.LIST, focusContent = false); return }
        lifecycleScope.launch {
            val kind = when (section) { Section.MOVIES -> Kind.MOVIE; Section.SERIES -> Kind.SERIES; else -> null }
            val cont = withContext(Dispatchers.Default) { continueRow(kind) }
            replaceRow(CONTINUE, cont, 0)
            if (section == Section.HOME) {
                val list = withContext(Dispatchers.Default) { listRow() }
                val at = adapter.rows.indexOfFirst { it.id.startsWith("top10") } + 1
                replaceRow(MYLIST, list, at.coerceAtLeast(0))
            }
            if (currentFocus == null) focusContent()
        }
    }

    private fun replaceRow(id: String, row: Row?, insertAt: Int) {
        val rows = adapter.rows.toMutableList()
        val idx = rows.indexOfFirst { it.id == id }
        when {
            row != null && idx >= 0 -> { rows[idx] = row; adapter.rows = rows; adapter.notifyItemChanged(idx) }
            row != null -> { val at = insertAt.coerceIn(0, rows.size); rows.add(at, row); adapter.rows = rows; adapter.notifyItemInserted(at) }
            idx >= 0 -> { rows.removeAt(idx); adapter.rows = rows; adapter.notifyItemRemoved(idx) }
        }
    }

    // ================================================================== billboard / en-tête

    private fun buildDots() {
        dots.removeAllViews()
        if (featured.size < 2) return
        featured.indices.forEach {
            dots.addView(View(this).apply { setBackgroundResource(R.drawable.dot) },
                LinearLayout.LayoutParams(6.dp, 6.dp).apply { marginEnd = 7.dp })
        }
        updateDots()
    }

    private fun updateDots() {
        for (i in 0 until dots.childCount) {
            val d = dots.getChildAt(i)
            val on = i == featuredIndex
            d.animate().alpha(if (on) 1f else 0.3f).scaleX(if (on) 1.3f else 1f).scaleY(if (on) 1.3f else 1f).setDuration(250).start()
        }
    }

    private fun enterBillboard(focus: Boolean) {
        if (featured.isEmpty()) return
        billboard = true
        heroButtons.fade(true)
        featured.getOrNull(featuredIndex)?.let { showItem(it, force = true) }
        updateDots()
        if (focus) btnPlay.requestFocus()
        startRotation()
    }

    private fun exitBillboard() {
        billboard = false
        rotateJob?.cancel()
        heroButtons.fade(false, gone = false)
    }

    private fun startRotation() {
        rotateJob?.cancel()
        if (featured.size < 2) return
        rotateJob = lifecycleScope.launch {
            while (billboard) {
                delay(9_000)
                if (!billboard || navOpen) break
                featuredIndex = (featuredIndex + 1) % featured.size
                showItem(featured[featuredIndex])
                updateDots()
            }
        }
    }

    private fun showItem(item: Item, force: Boolean = false) {
        if (!force && shownKey == item.key) return
        val changed = shownKey != item.key
        shownKey = item.key
        val d = Repo.cachedDetails(item)

        if (changed) {
            heroInfo.animate().cancel()
            heroInfo.alpha = 0f; heroInfo.translationX = 12f.dpf
            heroInfo.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
        heroKicker.text = Fmt.kicker(item.kind)
        heroTitle.textSize = Fmt.titleSize(item.name)
        heroTitle.text = item.name
        heroMeta.text = Fmt.meta(item, d)
        heroPlot.text = d?.plot ?: item.plot ?: ""
        if (billboard) updatePlayLabel(item)

        val wide = d?.backdrop ?: item.backdrop
        val url = wide ?: item.icon
        if (url != heroUrl) {
            heroUrl = url
            Img.hero(heroImage, url, isPoster = wide == null)
            ambient.fromUrl(url)
        }

        // Les listes ne contiennent pas toujours résumé et visuel large : on les charge si l'on s'attarde
        if (d == null && item.kind != Kind.LIVE) {
            heroJob?.cancel()
            heroJob = lifecycleScope.launch {
                delay(450)
                runCatching { Repo.details(item) }.getOrNull() ?: return@launch
                if (shownKey == item.key) showItem(item, force = true)
            }
        }
    }

    private fun updatePlayLabel(item: Item) {
        btnPlayText.text = when (item.kind) {
            Kind.MOVIE -> if (Prefs.progress(item.key) in 0.01f..0.95f) "Reprendre" else "Lecture"
            Kind.SERIES -> Prefs.episodeMeta(item.id)?.let { "Reprendre S${it.season}:É${it.num}" } ?: "Lecture"
            Kind.LIVE -> "Regarder"
        }
    }

    // ================================================================== actions

    private fun openDetails(item: Item) {
        if (item.kind == Kind.LIVE) { Play.live(this, listOf(item), 0); return }
        startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_KEY, item.key))
    }

    /** Lecture directe (comme depuis "Reprendre la lecture" sur Netflix). */
    private fun playItem(item: Item) {
        when (item.kind) {
            Kind.MOVIE -> Play.movie(this, item, resume = true)
            Kind.LIVE -> Play.live(this, listOf(item), 0)
            Kind.SERIES -> {
                busy.fade(true)
                lifecycleScope.launch {
                    try {
                        Play.series(this@MainActivity, item, Repo.details(item))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast("Impossible de charger les épisodes")
                    } finally {
                        busy.fade(false)
                    }
                }
            }
        }
    }

    override fun onCardFocused(item: Item, row: Row?) {
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(140)
            showItem(item)
        }
    }

    override fun onCardClicked(item: Item, row: Row?) {
        when {
            row?.id == CONTINUE -> playItem(item)
            item.kind == Kind.LIVE -> {
                val list = row?.items?.filter { it.kind == Kind.LIVE } ?: listOf(item)
                Play.live(this, list, list.indexOfFirst { it.key == item.key }.coerceAtLeast(0))
            }
            else -> openDetails(item)
        }
    }

    override fun onCardLongPressed(item: Item, row: Row?) {
        val inList = Prefs.isFav(item.key)
        val options = ArrayList<Pair<String, () -> Unit>>()
        options += (if (item.kind == Kind.LIVE) "Regarder" else "Lecture") to { playItem(item) }
        if (item.kind != Kind.LIVE) options += "Plus d'infos" to { openDetails(item) }
        options += (if (inList) "Retirer de Ma liste" else "Ajouter à Ma liste") to {
            toggleFavorite(item); refreshPersonalRows()
        }
        if (row?.id == CONTINUE) options += "Retirer de « Reprendre la lecture »" to {
            Prefs.removeRecent(item.key); refreshPersonalRows()
        }
        showMenu(item.name, Fmt.meta(item, Repo.cachedDetails(item)).toString(), options)
    }

    companion object {
        private const val CONTINUE = "continue"
        private const val MYLIST = "mylist"
    }
}
