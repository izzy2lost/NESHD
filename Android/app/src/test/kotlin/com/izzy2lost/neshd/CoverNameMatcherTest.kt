package com.izzy2lost.neshd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverNameMatcherTest {
    @Test
    fun matchesChipAndDaleDespiteDisneyBranding() {
        val index = mutableMapOf<String, String>()
        CoverNameMatcher.indexCandidates("Disney's Chip 'n Dale Rescue Rangers")
            .forEach { index[it] = "Disney's Chip 'n Dale Rescue Rangers.webp" }
        CoverNameMatcher.indexCandidates("Disney's Chip 'n Dale Rescue Rangers 2")
            .forEach { index[it] = "Disney's Chip 'n Dale Rescue Rangers 2.webp" }

        val match = CoverNameMatcher.findContainedTitleMatch(
            CoverNameMatcher.lookupCandidates("Chip 'n Dale - Rescue Rangers (USA).nes"),
            index
        )

        assertEquals("Disney's Chip 'n Dale Rescue Rangers.webp", match)
    }

    @Test
    fun doesNotFuzzyMatchShortGenericTitles() {
        val index = mapOf(
            CoverNameMatcher.normalizeKey("Batman Returns") to "Batman Returns.webp"
        )

        val match = CoverNameMatcher.findContainedTitleMatch(
            CoverNameMatcher.lookupCandidates("Batman.nes"),
            index
        )

        assertNull(match)
    }

    @Test
    fun exactCandidatesStillNormalizeDecorationsAndArticles() {
        assertEquals(
            linkedSetOf("legendofzeldathe", "thelegendofzelda"),
            CoverNameMatcher.lookupCandidates("Legend of Zelda, The (USA) [!].nes")
        )
    }
}
