package com.example.georescux.data.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the offline place search: parse, diacritic-insensitive name
 * resolution and autocomplete suggestions.
 */
class PlaceIndexTest {

    private val places = listOf(
        PlaceEntry("Haridwār", "city", 29.95, 78.16, "1211180500", true),
        PlaceEntry("Dehradun", "city", 30.3165, 78.0322, "299531301", true),
        PlaceEntry("Dehradun Cantonment", "town", 30.32, 78.01, "111", false),
        PlaceEntry("Rishikesh", "town", 30.0869, 78.2676, "3735821429", true),
        PlaceEntry("District Hospital", "hospital", 30.1, 78.2, "222", false),
    )

    @Test
    fun `parses places json`() {
        val json = """
            {"region":"uttarakhand","places":[
              {"name":"Dehradun","kind":"city","latitude":30.3165,"longitude":78.0322,
               "nodeId":"299531301","isSafeHaven":true},
              {"name":"Rishikesh","kind":"town","latitude":30.0869,"longitude":78.2676,
               "nodeId":"3735821429","isSafeHaven":true}
            ]}
        """.trimIndent()
        val parsed = PlaceIndex.parse(json)
        assertEquals(2, parsed.size)
        assertEquals("Dehradun", parsed[0].name)
        assertEquals("299531301", parsed[0].nodeId)
        assertEquals(true, parsed[0].isSafeHaven)
    }

    @Test
    fun `exact name resolves case and diacritic insensitively`() {
        assertEquals("1211180500", PlaceIndex.find(places, "haridwar")?.nodeId)
        assertEquals("1211180500", PlaceIndex.find(places, "Haridwār")?.nodeId)
    }

    @Test
    fun `exact match beats prefix siblings`() {
        assertEquals("299531301", PlaceIndex.find(places, "Dehradun")?.nodeId)
    }

    @Test
    fun `unknown query resolves to null`() {
        assertNull(PlaceIndex.find(places, "Mumbai"))
    }

    @Test
    fun `suggestions rank prefix matches first`() {
        val names = PlaceIndex.suggestions(places, "dehra", limit = 5)
        assertEquals("Dehradun", names.first())
        assertEquals("Dehradun Cantonment", names[1])
    }

    @Test
    fun `empty query yields nothing`() {
        assertEquals(0, PlaceIndex.suggestions(places, "").size)
        assertNull(PlaceIndex.find(places, "  "))
    }
}
