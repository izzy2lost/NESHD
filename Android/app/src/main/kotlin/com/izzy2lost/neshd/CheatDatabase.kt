package com.izzy2lost.neshd

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object CheatDatabase {
    private const val ASSET_NAME = "CheatDb.Nes.json"
    private const val PREF_PREFIX = "enabled_cheats_"
    private const val NES_PRG_BANK_SIZE = 16 * 1024

    data class CheatEntry(val description: String, val code: String)
    data class GameEntry(val name: String, val sha1: String, val cheats: List<CheatEntry>)

    @Volatile
    private var cachedGames: List<GameEntry>? = null

    fun findGame(context: Context, sha1: String): GameEntry? {
        val normalizedHash = sha1.uppercase()
        return loadGames(context).firstOrNull { it.sha1 == normalizedHash }
    }

    fun enabledCheatCodes(context: Context, sha1: String): Set<String> {
        return prefs(context).getStringSet(PREF_PREFIX + sha1.uppercase(), emptySet()).orEmpty()
    }

    fun setCheatEnabled(context: Context, sha1: String, code: String, enabled: Boolean) {
        val normalizedHash = sha1.uppercase()
        val enabledCodes = enabledCheatCodes(context, normalizedHash).toMutableSet()
        if (enabled) {
            enabledCodes.add(code)
        } else {
            enabledCodes.remove(code)
        }

        prefs(context).edit()
            .putStringSet(PREF_PREFIX + normalizedHash, enabledCodes)
            .apply()
    }

    fun enabledCodeParts(context: Context, sha1: String): Array<String> {
        val game = findGame(context, sha1) ?: return emptyArray()
        val enabledCodes = enabledCheatCodes(context, sha1)
        return game.cheats
            .asSequence()
            .filter { it.code in enabledCodes }
            .flatMap { it.code.splitToSequence(';') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .toTypedArray()
    }

    fun computeNesCheatHash(context: Context, pathOrUri: String): String? {
        val bytes = openRomBytes(context, pathOrUri) ?: return null
        val prgRom = extractInesPrgRom(bytes)
        return sha1Hex(prgRom ?: bytes)
    }

    private fun loadGames(context: Context): List<GameEntry> {
        cachedGames?.let { return it }

        return synchronized(this) {
            cachedGames ?: readGames(context.applicationContext).also { cachedGames = it }
        }
    }

    private fun readGames(context: Context): List<GameEntry> {
        val json = context.assets.open(ASSET_NAME).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
        }
        val games = JSONObject(json).getJSONArray("games")
        return List(games.length()) { gameIndex ->
            val game = games.getJSONObject(gameIndex)
            val cheats = game.getJSONArray("cheats")
            GameEntry(
                name = game.getString("name"),
                sha1 = game.getString("sha1").uppercase(),
                cheats = List(cheats.length()) { cheatIndex ->
                    val cheat = cheats.getJSONObject(cheatIndex)
                    CheatEntry(
                        description = cheat.getString("desc"),
                        code = cheat.getString("code")
                    )
                }
            )
        }
    }

    private fun openRomBytes(context: Context, pathOrUri: String): ByteArray? {
        val uri = Uri.parse(pathOrUri)
        return try {
            val stream = when (uri.scheme) {
                "content" -> context.contentResolver.openInputStream(uri)
                "file" -> uri.path?.let { File(it).inputStream() }
                null, "" -> File(pathOrUri).inputStream()
                else -> File(pathOrUri).inputStream()
            }
            stream?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractInesPrgRom(bytes: ByteArray): ByteArray? {
        if (bytes.size < 16 ||
            bytes[0] != 'N'.code.toByte() ||
            bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'S'.code.toByte() ||
            bytes[3] != 0x1A.toByte()) {
            return null
        }

        val flags6 = bytes[6].toInt() and 0xFF
        val flags7 = bytes[7].toInt() and 0xFF
        val hasTrainer = (flags6 and 0x04) != 0
        val isNes2 = (flags7 and 0x0C) == 0x08

        var prgBankCount = bytes[4].toInt() and 0xFF
        if (isNes2) {
            val prgMsb = bytes[9].toInt() and 0x0F
            if (prgMsb != 0x0F) {
                prgBankCount = prgBankCount or (prgMsb shl 8)
            }
        }

        val offset = 16 + if (hasTrainer) 512 else 0
        val size = prgBankCount * NES_PRG_BANK_SIZE
        if (size <= 0 || offset < 0 || offset + size > bytes.size) {
            return null
        }

        return bytes.copyOfRange(offset, offset + size)
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(NativeLib.PREFS_NAME, Context.MODE_PRIVATE)
}
