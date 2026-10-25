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

    @Test
    fun displayTitleDropsTagsFromExtractedZipNames() {
        assertEquals(
            "Wrath of the Black Manta",
            CoverNameMatcher.displayTitle("Wrath of the Black Manta _USA_ _Rev 1_.nes")
        )
        assertEquals("Shadow of the Ninja", CoverNameMatcher.displayTitle("Shadow of the Ninja _USA_.nes"))
        assertEquals("Shadow of the Ninja", CoverNameMatcher.displayTitle("Shadow of the Ninja_USA_.nes"))
        assertEquals("10-Yard Fight", CoverNameMatcher.displayTitle("10-Yard Fight _USA_ Europe_.nes"))
    }

    @Test
    fun displayTitleDropsParenthesizedTags() {
        assertEquals("Shadow of the Ninja", CoverNameMatcher.displayTitle("Shadow of the Ninja (USA).nes"))
        assertEquals("10-Yard Fight", CoverNameMatcher.displayTitle("10-Yard Fight (USA, Europe)"))
        assertEquals("Contra", CoverNameMatcher.displayTitle("Contra (USA) [!].nes"))
    }

    @Test
    fun displayTitleRestoresPunctuationAndArticles() {
        assertEquals("The Legend of Zelda", CoverNameMatcher.displayTitle("Legend of Zelda, The"))
        assertEquals("The Legend of Zelda", CoverNameMatcher.displayTitle("Legend of Zelda_ The _USA_.nes"))
        assertEquals(
            "Mike Tyson's Punch-Out",
            CoverNameMatcher.displayTitle("Mike Tyson_s Punch-Out__ _USA_.nes")
        )
        assertEquals("Super Mario Bros", CoverNameMatcher.displayTitle("Super_Mario_Bros.nes"))
        assertEquals(
            "Wai Wai World 2 - SOS Paseri Jou",
            CoverNameMatcher.displayTitle("Wai Wai World 2 - SOS__ Paseri Jou _Japan_.nes")
        )
    }

    @Test
    fun matchesTranslatedRomsExtractedFromZips() {
        val index = mapOf(
            CoverNameMatcher.normalizeKey("Final Fantasy") to "Final Fantasy.webp",
            CoverNameMatcher.normalizeKey("Final Fantasy II") to "Final Fantasy II.webp",
            CoverNameMatcher.normalizeKey("Final Fantasy III") to "Final Fantasy III.webp"
        )

        fun lookup(name: String) =
            CoverNameMatcher.lookupCandidates(name).firstNotNullOfOrNull { index[it] }

        assertEquals(
            "Final Fantasy II.webp",
            lookup("Final Fantasy II _Japan_ _T-En by Demiforce v1.03_ _Add by Chaos Rush v2.2b_.nes")
        )
        assertEquals(
            "Final Fantasy III.webp",
            lookup("Final Fantasy III _Japan_ _T-En by Chaos Rush v1.3_.nes")
        )
    }
}
