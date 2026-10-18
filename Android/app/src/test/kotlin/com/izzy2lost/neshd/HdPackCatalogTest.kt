package com.izzy2lost.neshd

import org.junit.Assert.assertEquals
import org.junit.Test

class HdPackCatalogTest {
    private fun pack(title: String, aliases: List<String> = emptyList(), roms: Set<String> = emptySet()) =
        HdPackCatalog.Pack(title.lowercase(), title, aliases, "", "", 0, "", roms)

    private val packs = listOf(
        pack("Castlevania HD", listOf("Castlevania")),
        pack("Castlevania III: Dracula's Curse HD", listOf("Castlevania III")),
        pack("Contra HD", listOf("Contra"), setOf("C9EA66BB7CB30AD5343F1721B1D4D3219859319B")),
        pack("Super Mario Bros. HD", listOf("Super Mario Bros"))
    )

    private fun matches(romName: String, sha1: String? = null) =
        HdPackCatalog.rank(packs, romName, sha1)
            .filter { it.second != HdPackCatalog.Match.NONE }
            .map { it.first.title to it.second }

    @Test
    fun romHashMatchRanksFirst() {
        val ranked = HdPackCatalog.rank(packs, "whatever.nes", "C9EA66BB7CB30AD5343F1721B1D4D3219859319B")
        assertEquals("Contra HD" to HdPackCatalog.Match.ROM_HASH, ranked.first().let { it.first.title to it.second })
    }

    @Test
    fun titleMatchesIgnoreRegionTags() {
        assertEquals(
            listOf("Super Mario Bros. HD" to HdPackCatalog.Match.TITLE),
            matches("Super Mario Bros. (World).nes")
        )
        assertEquals(
            listOf("Castlevania HD" to HdPackCatalog.Match.TITLE),
            matches("Castlevania (USA) (Rev 1).nes")
        )
    }

    @Test
    fun sequelDoesNotMatchOriginal() {
        assertEquals(
            listOf("Castlevania III: Dracula's Curse HD" to HdPackCatalog.Match.TITLE),
            matches("Castlevania III - Dracula's Curse (USA).nes")
        )
    }
}
