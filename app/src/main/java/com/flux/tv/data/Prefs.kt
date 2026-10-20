package com.flux.tv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Réglages + données par profil.
 * Tout ce qui est personnel (historique, positions de lecture, Ma liste, J'aime)
 * est préfixé par l'identifiant du profil : chaque membre du foyer a son propre Flux.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("flux", Context.MODE_PRIVATE)
    }

    private fun str(k: String, def: String = "") = sp.getString(k, def) ?: def
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()

    // ------------------------------------------------------------------ compte
    var server: String get() = str("server"); set(v) = put("server", v)
    var user: String get() = str("user"); set(v) = put("user", v)
    var pass: String get() = str("pass"); set(v) = put("pass", v)
    val hasAccount get() = server.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()
    /** Écriture immédiate sur disque : le compte survit même à une coupure de courant juste après. */
    fun saveAccount(s: String, u: String, p: String) =
        sp.edit().putString("server", s).putString("user", u).putString("pass", p).commit()
    fun clearAccount() = sp.edit().remove("server").remove("user").remove("pass").apply()

    // ------------------------------------------------------------------ réglages globaux
    var liveFormat: String get() = str("liveFormat", "ts"); set(v) = put("liveFormat", v)
    var bufferProfile: Int get() = sp.getInt("buffer", 1); set(v) = sp.edit().putInt("buffer", v).apply()
    var livePreview: Boolean get() = sp.getBoolean("preview", true); set(v) = sp.edit().putBoolean("preview", v).apply()
    var tunneling: Boolean get() = sp.getBoolean("tunneling", false); set(v) = sp.edit().putBoolean("tunneling", v).apply()
    /** 0 = petits, 1 = moyens, 2 = grands sous-titres */
    var subtitleSize: Int get() = sp.getInt("subSize", 1); set(v) = sp.edit().putInt("subSize", v).apply()
    var autoplayNext: Boolean get() = sp.getBoolean("autoNext", true); set(v) = sp.edit().putBoolean("autoNext", v).apply()

    // ------------------------------------------------------------------ profils
    val PROFILE_COLORS = intArrayOf(
        0xFFE50914.toInt(), 0xFF2D7FF9.toInt(), 0xFFF2B705.toInt(), 0xFF1DB954.toInt(),
        0xFF8E44AD.toInt(), 0xFFE67E22.toInt(), 0xFF16A085.toInt(),
    )

    fun profiles(): List<Profile> = str("profiles").split('\n').mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 3) null else Profile(p[0].toIntOrNull() ?: return@mapNotNull null, p[1], p[2].toIntOrNull() ?: 0)
    }

    private fun saveProfiles(list: List<Profile>) =
        put("profiles", list.joinToString("\n") { "${it.id}\t${it.name.replace('\t', ' ')}\t${it.color}" })

    fun addProfile(name: String, color: Int): Profile {
        val list = profiles()
        val p = Profile((list.maxOfOrNull { it.id } ?: 0) + 1, name, color)
        saveProfiles(list + p)
        return p
    }

    fun updateProfile(p: Profile) = saveProfiles(profiles().map { if (it.id == p.id) p else it })

    fun deleteProfile(id: Int) {
        saveProfiles(profiles().filter { it.id != id })
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("u$id:") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Crée un premier profil si aucun n'existe. */
    fun ensureProfile(): Profile = profiles().firstOrNull() ?: addProfile("Moi", 0)

    var profileId: Int get() = sp.getInt("profile", -1); set(v) = sp.edit().putInt("profile", v).apply()
    val profile: Profile? get() = profiles().firstOrNull { it.id == profileId }

    private fun k(key: String) = "u$profileId:$key"

    // ------------------------------------------------------------------ Ma liste & J'aime
    fun favorites(): Set<String> = sp.getStringSet(k("fav"), emptySet()) ?: emptySet()
    fun isFav(key: String) = favorites().contains(key)
    fun toggleFav(key: String): Boolean = toggleIn("fav", key)

    fun isLiked(key: String) = (sp.getStringSet(k("like"), emptySet()) ?: emptySet()).contains(key)
    fun toggleLike(key: String): Boolean = toggleIn("like", key)

    private fun toggleIn(set: String, key: String): Boolean {
        val s = HashSet(sp.getStringSet(k(set), emptySet()) ?: emptySet())
        val added = if (s.remove(key)) false else { s.add(key); true }
        sp.edit().putStringSet(k(set), s).apply()
        return added
    }

    // ------------------------------------------------------------------ historique
    fun recents(): List<String> = str(k("recent")).split('\n').filter { it.isNotEmpty() }
    fun pushRecent(key: String) {
        val l = recents().toMutableList(); l.remove(key); l.add(0, key)
        put(k("recent"), l.take(40).joinToString("\n"))
    }
    fun removeRecent(key: String) = put(k("recent"), recents().filter { it != key }.joinToString("\n"))

    fun recentChannels(): List<String> = str(k("recentCh")).split('\n').filter { it.isNotEmpty() }
    fun pushRecentChannel(key: String) {
        val l = recentChannels().toMutableList(); l.remove(key); l.add(0, key)
        put(k("recentCh"), l.take(15).joinToString("\n"))
    }

    var lastChannel: String get() = str(k("lastCh")); set(v) = put(k("lastCh"), v)

    fun clearHistory() {
        val e = sp.edit()
        val prefix = "u$profileId:"
        sp.all.keys.filter {
            it.startsWith(prefix) && it.removePrefix(prefix).let { s ->
                s.startsWith("p_") || s.startsWith("d_") || s.startsWith("le_") || s.startsWith("em_") || s == "recent" || s == "recentCh"
            }
        }.forEach { e.remove(it) }
        e.apply()
    }

    // ------------------------------------------------------------------ positions de lecture
    fun savePos(key: String, pos: Long, dur: Long) =
        sp.edit().putLong(k("p_$key"), pos).putLong(k("d_$key"), dur).apply()
    fun pos(key: String) = sp.getLong(k("p_$key"), 0L)
    fun dur(key: String) = sp.getLong(k("d_$key"), 0L)
    /** Progression 0..1, ou -1 si jamais regardé. */
    fun progress(key: String): Float {
        val d = dur(key); val p = pos(key)
        return if (d > 0 && p > 0) (p.toFloat() / d).coerceIn(0f, 1f) else -1f
    }
    fun isWatched(key: String) = progress(key) >= 0.92f
    fun remainingMs(key: String): Long = (dur(key) - pos(key)).coerceAtLeast(0)

    // ------------------------------------------------------------------ séries
    fun lastEpisode(seriesId: String): String? = sp.getString(k("le_$seriesId"), null)

    fun setLastEpisode(seriesId: String, ep: Episode) {
        sp.edit()
            .putString(k("le_$seriesId"), ep.id)
            .putString(k("em_$seriesId"), listOf(ep.id, ep.season, ep.num, ep.image ?: "", ep.title.replace('\t', ' ')).joinToString("\t"))
            .apply()
    }

    fun episodeMeta(seriesId: String): EpisodeMeta? {
        val p = str(k("em_$seriesId")).split('\t')
        if (p.size < 5) return null
        return EpisodeMeta(p[0], p[1].toIntOrNull() ?: 1, p[2].toIntOrNull() ?: 1, p[4], p[3].ifEmpty { null })
    }

    // ------------------------------------------------------------------ langues préférées
    var audioLang: String get() = str(k("audioLang"), "fr"); set(v) = put(k("audioLang"), v)
    var subLang: String get() = str(k("subLang")); set(v) = put(k("subLang"), v)
}
