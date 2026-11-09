package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryTest {
    @Test
    fun primaryIsArtistCommaTitleAndSingleVersionWithYear() {
        assertEquals("Amy Winehouse, Rehab [Single Version, 2006]", SearchQuery.primary("Rehab", "Amy Winehouse", 2006))
        assertEquals("Amy Winehouse, Rehab [Single Version]", SearchQuery.primary("Rehab", "Amy Winehouse", null))
    }

    @Test
    fun theTitleExtraIsTheExtendedTitle() {
        assertEquals("Rehab [Single Version, 2006]", SearchQuery.extendedTitle("Rehab", 2006))
        assertEquals("Rehab [Single Version]", SearchQuery.extendedTitle("Rehab", null))
    }

    @Test
    fun secondAttemptIsTheClassicQueryWithPlainTitle() {
        assertEquals("Rehab Amy Winehouse", SearchQuery.fallback("Rehab", "Amy Winehouse"))
        assertEquals("Rehab", SearchQuery.titleExtra("Rehab", 2006, true))
        assertEquals("Rehab [Single Version, 2006]", SearchQuery.titleExtra("Rehab", 2006, false))
    }
}
