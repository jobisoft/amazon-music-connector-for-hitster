package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryTest {
    @Test
    fun theQueryIsArtistCommaTitle() {
        assertEquals("Amy Winehouse, Rehab", SearchQuery.query("Rehab", "Amy Winehouse"))
        assertEquals("Toploader, Dancing in the Moonlight", SearchQuery.query("Dancing in the Moonlight", "Toploader"))
    }
}
