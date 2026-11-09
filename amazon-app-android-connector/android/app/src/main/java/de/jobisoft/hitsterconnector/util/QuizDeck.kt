package de.jobisoft.hitsterconnector.util

import de.jobisoft.hitsterconnector.data.repository.CSVSong
import java.text.Normalizer
import kotlin.random.Random

/** A card drawn for the quiz: the card set file and the song. */
data class QuizCard(val setFile: String, val song: CSVSong)

/** The cards of the selected sets, and what makes two of them "the same song". */
object QuizPicker {

    /** All cards of the selected sets (a selected set that is not loaded, or is empty, adds nothing). */
    fun pool(sets: Map<String, List<CSVSong>>, selected: Collection<String>): List<QuizCard> =
        selected.distinct().flatMap { file -> sets[file].orEmpty().map { QuizCard(file, it) } }

    private val brackets = Regex("\\(.*?\\)|\\[.*?\\]")
    private val versionSuffix = Regex("\\s-\\s[^-]*\\b(remaster|remastered|version|mix|edit|live|mono|stereo|explicit|single)\\b.*$")

    /** Title or artist without "(Remastered)", "[Explicit]", " - 2011 Remaster", accents, case and punctuation. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(brackets, " ")
            .replace(versionSuffix, "")
            .replace(Regex("[^a-z0-9]+"), "")

    /** The same song in two editions has the same key. */
    fun songKey(song: CSVSong): String = normalize(song.title) + "|" + normalize(song.artist)
}

/**
 * The quiz draws WITHOUT replacement: the cards of the selected sets are shuffled into a deck, one
 * after the other comes out, and none comes twice until the deck is used up. The same song in
 * several editions (same normalized title and artist) is in the deck only once. When the deck is
 * empty it is shuffled again, and its first card is not the one played last.
 */
class QuizDeck(
    sets: Map<String, List<CSVSong>>,
    selected: Collection<String>,
    private val random: Random = Random.Default
) {
    /** What [next] gives: the card, how many are left in the deck, and whether the deck was shuffled again for it. */
    data class Draw(val card: QuizCard, val remaining: Int, val deckSize: Int, val reshuffled: Boolean)

    private val pool = QuizPicker.pool(sets, selected)
    private var deck = ArrayDeque<QuizCard>()
    private var lastPlayed: QuizCard? = null
    private var played = 0

    /** Cards in a full deck (every song once). */
    val deckSize: Int = buildDeck().size

    val remaining: Int get() = deck.size

    /** A shuffled deck with every song once. */
    private fun buildDeck(): List<QuizCard> {
        val seen = HashSet<String>()
        return pool.shuffled(random).filter { seen.add(QuizPicker.songKey(it.song)) }
    }

    /** The next card, or null if the selected sets have no cards at all. */
    fun next(): Draw? {
        if (pool.isEmpty()) return null
        var reshuffled = false
        if (deck.isEmpty()) {
            reshuffled = played > 0
            val fresh = ArrayDeque(buildDeck())
            // the first card of a new deck is not the song played last, if there is another one
            val last = lastPlayed
            if (last != null && fresh.size > 1 && QuizPicker.songKey(fresh.first().song) == QuizPicker.songKey(last.song)) {
                val other = fresh.indexOfFirst { QuizPicker.songKey(it.song) != QuizPicker.songKey(last.song) }
                if (other > 0) {
                    val swap = fresh[other]
                    fresh[other] = fresh[0]
                    fresh[0] = swap
                }
            }
            deck = fresh
        }
        val card = deck.removeFirst()
        lastPlayed = card
        played++
        return Draw(card, deck.size, deckSize, reshuffled)
    }
}
