package com.flux.tv.data

enum class Kind(val prefix: Char) { LIVE('l'), MOVIE('m'), SERIES('s') }

/** Types de cartes. */
object CardStyle {
    const val POSTER = 0   // affiche 2:3
    const val LOGO = 1     // logo de chaîne
    const val WIDE = 2     // 16:9 avec progression (Reprendre la lecture)
    const val TOP10 = 3    // grand chiffre + affiche
}

class Category(val id: String, val name: String)

class Item(
    val kind: Kind,
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val ext: String,
    val num: Int,
    val rating: String?,
    val added: Long,
    val plot: String?,
    val backdrop: String?,
    val year: String?,
    /** Adresse de lecture fournie telle quelle par une playlist M3U (sinon construite depuis l'identifiant). */
    val direct: String? = null,
) {
    val key = "${kind.prefix}:$id"
    val ratingValue: Double get() = rating?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    /** Ajouté depuis moins de 21 jours. */
    val isNew: Boolean get() = added > 0 && System.currentTimeMillis() / 1000 - added < 21L * 86400
}

class Row(val title: String, val items: List<Item>, val style: Int = CardStyle.POSTER, val id: String = title)

class PlayItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val url: String,
    val isLive: Boolean,
    val logo: String?,
    val num: Int,
    val streamId: String,
    val episode: Episode? = null,
)

class AccountInfo(val status: String, val expires: Long?, val active: String, val max: String) {
    /** Message clair si l'abonnement ne donne plus accès au contenu, sinon null. */
    fun blockedReason(): String? {
        val st = status.lowercase()
        val date = expires?.let { java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(it * 1000)) }
        val expired = expires != null && expires * 1000 < System.currentTimeMillis()
        return when {
            st == "expired" || expired -> "Votre abonnement IPTV est expiré" + (date?.let { " (depuis le $it)" } ?: "") +
                ". Le serveur accepte la connexion mais ne fournit plus les chaînes, films et séries : renouvelez-le auprès de votre fournisseur."
            st.isNotEmpty() && st != "active" -> "Votre compte IPTV est « $status » : le serveur ne fournit pas le contenu. Contactez votre fournisseur."
            else -> null
        }
    }
}

class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val ext: String,
    val image: String?,
    val plot: String?,
    val duration: String?,
    val direct: String? = null,
) {
    val key get() = "e:$id"
    val label get() = "S$season:É$num"
}

class Details(
    val plot: String?,
    val genre: String?,
    val duration: String?,
    val director: String?,
    val cast: String?,
    val rating: String?,
    val year: String?,
    val backdrop: String?,
    val seasons: Map<Int, List<Episode>> = emptyMap(),
) {
    val allEpisodes: List<Episode> by lazy { seasons.values.flatten() }
}

class EpgEntry(val title: String, val start: Long, val end: Long, val desc: String?)

class Profile(val id: Int, val name: String, val color: Int)

/** Dernier épisode vu d'une série (pour la carte "Reprendre"). */
class EpisodeMeta(val episodeId: String, val season: Int, val num: Int, val title: String, val image: String?)
