package com.flux.tv.data

/**
 * Ne garde que le contenu en français (réglage « Langue du contenu »).
 *
 * Les fournisseurs IPTV signalent la langue dans le nom de la catégorie ou du titre :
 * « FR | Action », « |FR| TF1 HD », « VF », « FRENCH », « Québec », 🇫🇷…
 * Une série anglaise doublée (VF) est gardée ; la VO et la VOSTFR (sous-titrée) sont écartées.
 */
object FrenchFilter {
    /** Repères de contenu en français (mot entier, insensible à la casse). */
    private val fr = Regex(
        """(?i)(?<![\p{L}])(FR|FRA|FRANCE|FRENCH|TRUEFRENCH|FRAN[CÇ]AIS[E]?|VF|VFF|VFQ|VFI|VF2|""" +
            """QC|QU[EÉ]BEC|BELGIQUE|SUISSE|ROMANDE|TNT)(?![\p{L}])|🇫🇷"""
    )

    /** Version originale ou sous-titrée : pas en français à l'oreille. */
    private val notFr = Regex("""(?i)(?<![\p{L}])(VOSTFR|VOST|VO|SUBFRENCH)(?![\p{L}])""")

    fun isFrenchLabel(text: String) = fr.containsMatchIn(text) && !notFr.containsMatchIn(text)

    /**
     * Garde un élément si sa catégorie est française, ou si son propre titre l'est,
     * sauf si le titre indique une VO / VOSTFR.
     */
    fun keep(itemName: String, categoryIsFrench: Boolean): Boolean {
        if (notFr.containsMatchIn(itemName)) return false
        return categoryIsFrench || fr.containsMatchIn(itemName)
    }

    /**
     * Filtre une liste et ses catégories. Si le fournisseur ne signale aucune langue
     * (rien ne serait gardé), on garde tout plutôt que d'afficher un écran vide.
     */
    fun apply(items: List<Item>, cats: List<Category>): Pair<List<Item>, List<Category>> {
        val frenchCats = cats.filter { isFrenchLabel(it.name) }.mapTo(HashSet()) { it.id }
        val kept = items.filter { keep(it.name, it.categoryId in frenchCats) }
        if (kept.isEmpty()) return items to cats
        val used = kept.mapTo(HashSet()) { it.categoryId }
        return kept to cats.filter { it.id in used }
    }
}
