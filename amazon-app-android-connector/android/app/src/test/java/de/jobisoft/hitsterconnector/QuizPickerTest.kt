package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.repository.CSVSong
import de.jobisoft.hitsterconnector.util.QuizDeck
import de.jobisoft.hitsterconnector.util.QuizPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizPickerTest {

    private fun songs(prefix: String, n: Int) = (1..n).map { CSVSong(it, "$prefix title $it", "$prefix artist", 1980 + it) }

    private val sets = mapOf(
        "a.csv" to songs("a", 5),
        "b.csv" to songs("b", 3),
        "c.csv" to songs("c", 10),
        "empty.csv" to emptyList()
    )

    private fun drawAll(deck: QuizDeck, n: Int) = (1..n).map { deck.next()!! }

    @Test
    fun neverPicksFromAnUnselectedSet() {
        for (seed in 1..50) {
            val deck = QuizDeck(sets, listOf("a.csv", "b.csv"), Random(seed))
            for (draw in drawAll(deck, 40)) assertTrue(draw.card.setFile == "a.csv" || draw.card.setFile == "b.csv")
        }
    }

    @Test
    fun noCardComesTwiceUntilTheDeckIsUsedUp() {
        val deck = QuizDeck(sets, listOf("a.csv", "b.csv"), Random(3))
        assertEquals(8, deck.deckSize)
        val first = drawAll(deck, 8)
        assertEquals("every card once", 8, first.map { it.card.setFile to it.card.song.index }.toSet().size)
        assertTrue(first.none { it.reshuffled })
        assertEquals((7 downTo 0).toList(), first.map { it.remaining })
        // the ninth card needs a new deck
        val ninth = deck.next()!!
        assertTrue(ninth.reshuffled)
        assertEquals(7, ninth.remaining)
    }

    @Test
    fun theSameSongInSeveralEditionsIsInTheDeckOnlyOnce() {
        val editions = mapOf(
            "de.csv" to listOf(CSVSong(1, "Blue Monday", "New Order", 1983), CSVSong(2, "Tainted Love", "Soft Cell", 1981)),
            "refresh.csv" to listOf(
                CSVSong(7, "Blue Monday (Remastered)", "New Order", 1983),
                CSVSong(8, "Tainted Love [Explicit]", "Soft Cell", 1981),
                CSVSong(9, "Blue Monday - 2011 Remaster", "NEW ORDER", 1983),
                CSVSong(10, "Another One", "Someone", 1990)
            )
        )
        for (seed in 1..30) {
            val deck = QuizDeck(editions, listOf("de.csv", "refresh.csv"), Random(seed))
            assertEquals("Blue Monday, Tainted Love, Another One", 3, deck.deckSize)
            val keys = drawAll(deck, 3).map { QuizPicker.songKey(it.card.song) }
            assertEquals(3, keys.toSet().size)
        }
    }

    @Test
    fun theSameSongOnSeveralCardsOfAnEditionComesOncePerRound() {
        // "Kiss Me Thru The Phone" shows up at the cards 217, 205 and 217 of hitster-de-aaaa0019.csv
        val edition = mapOf(
            "hitster-de-aaaa0019.csv" to listOf(
                CSVSong(217, "Kiss Me Thru The Phone", "Soulja Boy", 2008),
                CSVSong(205, "Kiss Me Thru the Phone", "Soulja Boy", 2008),
                CSVSong(1, "Another Song", "Somebody", 2001),
                CSVSong(2, "Yet Another", "Somebody Else", 2002)
            )
        )
        for (seed in 1..200) {
            val deck = QuizDeck(edition, listOf("hitster-de-aaaa0019.csv"), Random(seed))
            assertEquals(3, deck.deckSize)
            // two rounds: the song is in each round once, never twice in a row
            val draws = drawAll(deck, 6)
            for (round in draws.chunked(3)) {
                assertEquals(1, round.count { QuizPicker.normalize(it.card.song.title) == QuizPicker.normalize("Kiss Me Thru The Phone") })
            }
            for ((a, b) in draws.zipWithNext()) assertNotEquals("seed $seed", QuizPicker.songKey(a.card.song), QuizPicker.songKey(b.card.song))
        }
    }

    @Test
    fun normalizingIgnoresCaseAccentsPunctuationAndVersionSuffixes() {
        assertEquals(QuizPicker.normalize("Don't Stop Me Now"), QuizPicker.normalize("dont stop me now (Remastered 2011)"))
        assertEquals(QuizPicker.normalize("Café del Mar"), QuizPicker.normalize("CAFE DEL MAR [Explicit]"))
        assertEquals(QuizPicker.normalize("Heroes"), QuizPicker.normalize("Heroes - 2017 Remaster"))
        assertNotEquals(QuizPicker.normalize("Heroes"), QuizPicker.normalize("Heroes Of Time"))
        assertNotEquals(
            QuizPicker.songKey(CSVSong(1, "Hello", "Adele", null)),
            QuizPicker.songKey(CSVSong(2, "Hello", "Lionel Richie", null))
        )
    }

    @Test
    fun theNewDeckDoesNotStartWithTheCardPlayedLast() {
        for (seed in 1..300) {
            val deck = QuizDeck(sets, listOf("b.csv"), Random(seed)) // 3 cards, repeats at the border are likely without the rule
            val round = drawAll(deck, 3)
            val next = deck.next()!!
            assertTrue(next.reshuffled)
            assertNotEquals("seed $seed", round.last().card, next.card)
        }
    }

    @Test
    fun emptyAndOneCardDecksDoNotBreak() {
        assertNull(QuizDeck(sets, emptyList()).next())
        assertNull(QuizDeck(sets, listOf("empty.csv")).next())
        assertNull(QuizDeck(sets, listOf("not-loaded.csv")).next())
        assertNull(QuizDeck(emptyMap(), listOf("a.csv")).next())
        assertEquals(0, QuizDeck(sets, listOf("empty.csv")).deckSize)
        // an empty or missing set next to a good one adds nothing and does not break the draw
        val mixed = QuizDeck(sets, listOf("empty.csv", "not-loaded.csv", "b.csv"), Random(1)).next()
        assertEquals("b.csv", mixed!!.card.setFile)
        // one card: it comes again and again, there is no other, and nothing hangs
        val one = QuizDeck(mapOf("x.csv" to songs("x", 1)), listOf("x.csv"), Random(1))
        val first = one.next()
        assertNotNull(first)
        assertFalse(first!!.reshuffled)
        repeat(5) {
            val again = one.next()!!
            assertEquals(1, again.card.song.index)
            assertTrue(again.reshuffled)
            assertEquals(0, again.remaining)
        }
    }

    @Test
    fun aSetSelectedTwiceIsNotMoreLikelyAndAllSetsGiveEveryCard() {
        assertEquals(5, QuizPicker.pool(sets, listOf("a.csv", "a.csv")).size)
        assertEquals(18, QuizDeck(sets, listOf("a.csv", "b.csv", "c.csv"), Random(5)).deckSize)
    }
}
