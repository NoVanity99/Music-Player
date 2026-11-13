package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable

/**
 * One name in an artist credit. [text] is how it's written in the credit; [name] is the library
 * artist it opens (only meaningful when [inLibrary]).
 */
@Immutable
data class ArtistPart(val text: String, val name: String, val inLibrary: Boolean)

/**
 * Splits credits like "Drake & Future" or "Tyler, The Creator feat. Kali Uchis" into artists.
 *
 * A credit is only split when at least one of its parts is an artist with songs of their own in the
 * library, so band names such as "Mumford & Sons" or "Earth, Wind & Fire" stay whole. Parts are
 * matched longest-first against library artists, which keeps "Tyler, The Creator" together.
 */
class ArtistIndex(credits: Collection<String>) {
    private val all: Map<String, String> = credits.associateBy(::key)

    /** Library artists: credits that aren't themselves a collaboration of other library artists. */
    private val solo: Map<String, String> = all.filterValues { credit ->
        val parts = segment(credit, all)
        parts.size < 2 || parts.none { key(it) in all }
    }

    /** Every library credit resolved up front, so lookups from the UI thread are read-only. */
    private val resolved: Map<String, List<ArtistPart>> = all.values.associateWith(::compute)

    /** The artists in [credit]: one part for a solo artist or band, several for a collaboration. */
    fun resolve(credit: String): List<ArtistPart> = resolved[credit] ?: compute(credit)

    private fun compute(credit: String): List<ArtistPart> {
        val parts = segment(credit, solo)
        return if (parts.size < 2 || parts.none { key(it) in solo }) {
            listOf(ArtistPart(credit.trim(), solo[key(credit)] ?: credit.trim(), key(credit) in solo))
        } else {
            parts.map { ArtistPart(it, solo[key(it)] ?: it, key(it) in solo) }.distinctBy { key(it.name) }
        }
    }

    fun isCollaboration(credit: String): Boolean = resolve(credit).size > 1

    /** The library artist called [name] (any letter case), if there is one. */
    fun libraryArtist(name: String): String? = solo[key(name)]

    companion object {
        /** "&", ",", ";", "+" and "feat." / "ft." / "featuring" between names. */
        private val separator = Regex("""\s*[&,;+]\s*|\s+(?:feat\.?|ft\.?|featuring)\s+""", RegexOption.IGNORE_CASE)

        internal fun key(name: String) = name.trim().lowercase()

        /**
         * Splits [credit] at separators, joining neighbouring pieces back together whenever the
         * longer name is in [known] (never the whole credit itself).
         */
        internal fun segment(credit: String, known: Map<String, String>): List<String> {
            val atoms = ArrayList<String>()
            val seps = ArrayList<String>()
            var last = 0
            for (m in separator.findAll(credit)) {
                atoms += credit.substring(last, m.range.first)
                seps += m.value
                last = m.range.last + 1
            }
            atoms += credit.substring(last)
            if (atoms.size < 2) return listOf(credit.trim())

            fun join(from: Int, to: Int) = buildString {
                for (k in from..to) {
                    append(atoms[k])
                    if (k < to) append(seps[k])
                }
            }.trim()

            val parts = ArrayList<String>()
            var i = 0
            while (i < atoms.size) {
                var end = i
                for (j in atoms.size - 1 downTo i + 1) {
                    if (i == 0 && j == atoms.size - 1) continue
                    if (key(join(i, j)) in known) { end = j; break }
                }
                join(i, end).takeIf { it.isNotEmpty() }?.let(parts::add)
                i = end + 1
            }
            return parts
        }
    }
}
