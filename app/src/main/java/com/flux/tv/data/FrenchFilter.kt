package com.flux.tv.data

/**
 * Filtre de langue « Français » d'un onglet (rien n'est supprimé : la recherche voit tout).
 *
 * Les fournisseurs IPTV signalent la langue dans le nom de la catégorie ou du titre :
 * « FR | Action », « |FR| TF1 HD », « VF », « EN | Netflix », « VOSTFR », 🇫🇷…
 * Règle : on cache seulement ce qui est explicitement dans une autre langue (ou en VO /
 * sous-titré). Ce qui n'indique aucune langue reste visible : beaucoup de catégories
 * françaises ne portent pas de mention (« Séries TV », « Netflix »…).
 */
object FrenchFilter {
    private const val L = """\p{L}"""

    /** Repères de contenu en français (mot entier). */
    private val fr = Regex(
        """(?i)(?<!$L)(FR|FRA|FRANCE|FRENCH|TRUEFRENCH|FRAN[CÇ]AIS[E]?|VF|VFF|VFQ|VFI|VF2|""" +
            """QC|QU[EÉ]BEC|BELGIQUE|SUISSE|ROMANDE)(?!$L)|🇫🇷"""
    )

    /** VO ou sous-titré : pas en français à l'oreille, même avec « FR » dans VOSTFR. */
    private val original = Regex("""(?i)(?<!$L)(VOSTFR|VOST|VO|SUBFRENCH|SUBBED|SUB)(?!$L)""")

    /** Noms de langues ou de pays écrits en entier (sans ambiguïté, n'importe où). */
    private val foreignWord = Regex(
        """(?i)(?<!$L)(ENGLISH|ANGLAIS|GERMAN|DEUTSCH|ALLEMAND|ITALIAN|ITALIA|ITALIEN|SPANISH|ESPA[NÑ]OL|""" +
            """LATINO|PORTUGU[EÊ]S|BRASIL|ARABIC|ARABE|TURKISH|T[UÜ]RK|HINDI|INDIA|POLSKA|POLISH|RUSSIAN|""" +
            """NEDERLANDS|DUTCH|GREEK|ROMANIA|ALBANIA|EX-?YU|KURD|PERSIAN|URDU|AFGHAN)(?!$L)"""
    )

    /**
     * Codes de langue courts, reconnus seulement en étiquette (« EN | », « |DE| », « [IT] »,
     * « UK: ») pour ne pas confondre un titre comme « It » ou « Us » avec un code.
     */
    private const val CODES = "EN|ENG|UK|US|USA|DE|GER|IT|ITA|ES|ESP|LAT|PT|BR|NL|AR|TR|IN|PL|RO|RU|AL|GR|SE|NO|DK|FI|KU|IR|PK"
    private val foreignTag = Regex(
        """(?i)(^\s*[\[(|]?\s*($CODES)\s*([\])|:]|\s-\s)|[|\[(]\s*($CODES)\s*[|\])])"""
    )

    private enum class Lang { FRENCH, FOREIGN, UNKNOWN }

    private fun lang(text: String): Lang = when {
        original.containsMatchIn(text) -> Lang.FOREIGN
        fr.containsMatchIn(text) -> Lang.FRENCH
        foreignWord.containsMatchIn(text) || foreignTag.containsMatchIn(text) -> Lang.FOREIGN
        else -> Lang.UNKNOWN
    }

    /** Le titre décide s'il indique une langue ; sinon c'est la catégorie. */
    private fun keep(itemName: String, category: Lang): Boolean = when (lang(itemName)) {
        Lang.FRENCH -> true
        Lang.FOREIGN -> false
        Lang.UNKNOWN -> category != Lang.FOREIGN
    }

    /** Vue filtrée d'une liste et de ses catégories. Si rien ne reste, on montre tout. */
    fun apply(items: List<Item>, cats: List<Category>): Pair<List<Item>, List<Category>> {
        val catLang = cats.associate { it.id to lang(it.name) }
        val kept = items.filter { keep(it.name, catLang[it.categoryId] ?: Lang.UNKNOWN) }
        if (kept.isEmpty()) return items to cats
        val used = kept.mapTo(HashSet()) { it.categoryId }
        return kept to cats.filter { it.id in used }
    }
}
