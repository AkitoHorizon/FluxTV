package com.flux.tv.data

import android.util.LruCache
import com.flux.tv.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Données en mémoire partagées par tous les écrans. */
object Repo {
    private var api: XtreamApi? = null
    private val locks = Kind.values().associateWith { Mutex() }
    // Tout le catalogue reste en mémoire (la recherche y puise) ; les écrans affichent la vue filtrée
    private val allCats = ConcurrentHashMap<Kind, List<Category>>()
    private val allItems = ConcurrentHashMap<Kind, List<Item>>()
    private val cats = ConcurrentHashMap<Kind, List<Category>>()
    private val items = ConcurrentHashMap<Kind, List<Item>>()
    private val byKey = ConcurrentHashMap<String, Item>()
    private val details = LruCache<String, Details>(200)
    private val top10 = ConcurrentHashMap<Kind, List<Item>>()

    @Volatile var generation = 0
        private set

    // File de lecture transmise au lecteur
    var queue: List<PlayItem> = emptyList()
    var queueIndex = 0
    var resume = true
    var seriesId: String? = null

    fun api(): XtreamApi = api ?: XtreamApi(Prefs.server, Prefs.user, Prefs.pass).also { api = it }

    fun isLoaded(kind: Kind) = items.containsKey(kind)
    /** Contenu affiché (selon le filtre de langue de l'onglet). */
    fun list(kind: Kind): List<Item> = items[kind].orEmpty()
    /** Tout le contenu, sans filtre (recherche). */
    fun all(kind: Kind): List<Item> = allItems[kind].orEmpty()
    fun categories(kind: Kind): List<Category> = cats[kind].orEmpty()
    fun categoryName(kind: Kind, id: String): String? = cats[kind]?.firstOrNull { it.id == id }?.name
    fun find(key: String): Item? = byKey[key]
    fun cachedDetails(item: Item): Details? = details.get(item.key)

    /** Comme [load], mais renvoie tout le contenu, sans le filtre de langue (recherche). */
    suspend fun loadAll(kind: Kind): List<Item> { load(kind); return all(kind) }

    /**
     * Change le filtre de langue d'un onglet : la vue est recalculée en arrière-plan
     * (des dizaines de milliers de titres), rien n'est retéléchargé.
     */
    suspend fun setFrenchOnly(kind: Kind, on: Boolean) {
        Prefs.setFrenchOnly(kind, on)
        withContext(Dispatchers.Default) { applyFilter(kind) }
        generation++
    }

    // ------------------------------------------------------------------ recherche

    private val accents = Regex("\\p{Mn}+")
    fun normalize(s: String): String =
        accents.replace(java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD), "")

    @Volatile private var searchIndex: List<Pair<Item, String>>? = null

    /** Index de recherche (tous les titres, toutes langues), construit une seule fois. */
    suspend fun searchIndex(): List<Pair<Item, String>> {
        searchIndex?.let { return it }
        val all = Kind.values().flatMap { k -> try { loadAll(k) } catch (e: IOException) { emptyList() } }
        return withContext(Dispatchers.Default) { all.map { it to normalize(it.name) } }.also { searchIndex = it }
    }

    private fun applyFilter(kind: Kind) {
        val list = allItems[kind] ?: return
        val c = allCats[kind].orEmpty()
        val (kept, keptCats) = if (Prefs.frenchOnly(kind)) FrenchFilter.apply(list, c) else list to c
        android.util.Log.i("FluxAPI", "$kind : ${kept.size}/${list.size} affichés")
        cats[kind] = keptCats
        items[kind] = kept
        top10.remove(kind)
    }

    suspend fun load(kind: Kind): List<Item> {
        items[kind]?.let { return it }
        return locks.getValue(kind).withLock {
            items[kind] ?: withContext(Dispatchers.IO) {
                // Copie compacte si elle est à jour, sinon analyse des listes du serveur (une fois)
                val (list, c) = CatalogStore.read(kind) ?: parseAndStore(kind)
                list.forEach { byKey[it.key] = it }
                allCats[kind] = c
                allItems[kind] = list
                applyFilter(kind)   // hors du fil de l'interface
                items.getValue(kind)
            }
        }
    }

    private suspend fun parseAndStore(kind: Kind): Pair<List<Item>, List<Category>> {
        val a = api()
        val raw = a.items(kind)
        // Sans catégories, le contenu reste affiché (rangée « Tous les … »)
        val c = try { a.categories(kind) } catch (e: IOException) {
            android.util.Log.w("FluxAPI", "catégories $kind indisponibles", e); emptyList()
        }
        val list = compact(kind, raw, a)
        CatalogStore.write(kind, list, c)
        return list to c
    }

    /**
     * Version allégée en mémoire : textes répétés partagés (catégories, extensions, notes, années),
     * résumés gardés seulement pour les séries (affichés sur l'accueil, les autres viennent de la fiche),
     * adresse de lecture gardée seulement si elle pointe vers un autre serveur.
     */
    private fun compact(kind: Kind, raw: List<Item>, a: XtreamApi): List<Item> {
        val pool = HashMap<String, String>()
        fun p(s: String) = pool.getOrPut(s) { s }
        return raw.map {
            Item(
                kind = kind, id = it.id, name = it.name, icon = it.icon,
                categoryId = p(it.categoryId), ext = p(it.ext), num = it.num, rating = it.rating?.let(::p),
                added = it.added, plot = if (kind == Kind.SERIES) it.plot else null, backdrop = it.backdrop,
                year = it.year?.let(::p), direct = a.keepDirect(it.direct),
            )
        }
    }

    suspend fun rows(kind: Kind): List<Row> = rowsOf(kind, load(kind))

    suspend fun rowsOf(kind: Kind, list: List<Item>): List<Row> = withContext(Dispatchers.Default) {
        val style = if (kind == Kind.LIVE) CardStyle.LOGO else CardStyle.POSTER
        val grouped = list.groupBy { it.categoryId }
        val cats = categories(kind)
        val rows = cats.mapNotNull { c ->
            grouped[c.id]?.takeIf { it.isNotEmpty() }?.let { Row(c.name, it, style, "cat:${kind.prefix}:${c.id}") }
        }
        // Éléments sans catégorie connue : on ne les perd pas
        val known = cats.mapTo(HashSet()) { it.id }
        val orphans = list.filter { it.categoryId !in known }
        val word = when (kind) { Kind.LIVE -> "Toutes les chaînes"; Kind.MOVIE -> "Tous les films"; Kind.SERIES -> "Toutes les séries" }
        if (orphans.isEmpty()) rows
        else rows + Row(if (rows.isEmpty()) word else "Autres", orphans, style, "cat:${kind.prefix}:_autres")
    }

    suspend fun details(item: Item): Details {
        details.get(item.key)?.let { return it }
        val d = if (item.kind == Kind.SERIES) api().seriesInfo(item.id) else api().vodInfo(item.id)
        details.put(item.key, d)
        return d
    }

    /** "Top 10 du moment" : les mieux notés parmi les ajouts récents. */
    fun top10(kind: Kind): List<Item> = top10.getOrPut(kind) {
        val recent = list(kind).sortedByDescending { it.added }.take(400)
        val rated = recent.filter { it.ratingValue > 0 }.sortedByDescending { it.ratingValue }
        (rated + recent).distinctBy { it.key }.take(10)
    }

    fun isTop10(item: Item) = item.kind != Kind.LIVE && top10(item.kind).any { it.key == item.key }

    fun latest(kind: Kind, n: Int) = list(kind).sortedByDescending { it.added }.take(n)

    fun similar(item: Item, n: Int = 30): List<Item> =
        list(item.kind).asSequence()
            .filter { it.categoryId == item.categoryId && it.key != item.key }
            .sortedWith(compareByDescending<Item> { it.ratingValue }.thenByDescending { it.added })
            .take(n).toList()

    /** Oublie le catalogue (mémoire + fichiers) : la prochaine ouverture refait la synchronisation complète. */
    fun clear() {
        api = null
        Prefs.catalogReady = false
        refreshJob?.cancel()
        CatalogSync.cancel()
        dropMemory()
        XtreamApi.catalogDir().deleteRecursively()
        File(App.instance.cacheDir, "api").deleteRecursively()   // ancien emplacement (v2.2)
    }

    /** Vide seulement la mémoire : les écrans se rechargent depuis les fichiers déjà téléchargés. */
    fun dropMemory() {
        searchIndex = null
        items.clear(); cats.clear(); allItems.clear(); allCats.clear(); byKey.clear(); details.evictAll(); top10.clear()
        generation++
    }

    /** Comme [dropMemory], depuis n'importe quel fil d'exécution. */
    fun dropMemoryAsync() { bg.launch(Dispatchers.Main) { dropMemory() } }

    // ------------------------------------------------------------------ mise à jour en arrière-plan

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var refreshJob: Job? = null

    /**
     * Si le catalogue a plus de 24 h, on le retélécharge discrètement : l'appli reste utilisable
     * avec l'ancien, qui n'est remplacé que liste par liste, une fois chacune reçue en entier.
     */
    fun refreshIfStale() {
        val age = XtreamApi.catalogAge() ?: return
        if (age < REFRESH_AFTER || refreshJob?.isActive == true) return
        val a = api()
        refreshJob = bg.launch {
            var changed = false
            for (action in XtreamApi.ACTIONS) {
                try { a.download(action, null); changed = true } catch (e: XtreamApi.AccountEchoException) {
                    // Serveur sans listes séparées : on refait le catalogue depuis sa liste complète
                    try { a.importM3u {}; changed = true } catch (e2: IOException) {
                        android.util.Log.w("FluxAPI", "mise à jour de fond (liste complète) échouée, on garde l'ancien catalogue", e2)
                    }
                    break
                } catch (e: IOException) {
                    android.util.Log.w("FluxAPI", "mise à jour de fond $action échouée, on garde l'ancienne liste", e)
                }
            }
            if (changed) withContext(Dispatchers.Main) { dropMemory() }
        }
    }

    private const val REFRESH_AFTER = 24L * 60 * 60 * 1000
}
