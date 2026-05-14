package com.izzy2lost.neshd

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object NesRomDatabase {
    private const val ASSET_NAME = "NesRomDb.json"

    enum class Region { NA, JP, FDS }

    data class TitleMatch(val title: String, val region: Region)

    @Volatile
    private var cachedNa: Map<String, String>? = null
    @Volatile
    private var cachedJp: Map<String, String>? = null
    @Volatile
    private var cachedFds: Map<String, String>? = null

    fun findTitle(context: Context, pathOrUri: String): String? =
        findMatch(context, pathOrUri)?.title

    fun findMatch(context: Context, pathOrUri: String): TitleMatch? {
        val bytes = openRomBytes(context, pathOrUri) ?: return null
        loadIfNeeded(context)
        val na = cachedNa ?: emptyMap()
        val jp = cachedJp ?: emptyMap()
        val fds = cachedFds ?: emptyMap()
        val candidateHashes = linkedSetOf(sha1Hex(bytes))
        extractHeaderlessNes(bytes)?.let { candidateHashes.add(sha1Hex(it)) }
        for (hash in candidateHashes) {
            fds[hash]?.let { return TitleMatch(it, Region.FDS) }
        }
        for (hash in candidateHashes) {
            jp[hash]?.let { return TitleMatch(it, Region.JP) }
        }
        for (hash in candidateHashes) {
            na[hash]?.let { return TitleMatch(it, Region.NA) }
        }
        return null
    }

    private fun loadIfNeeded(context: Context) {
        if (cachedNa != null && cachedJp != null && cachedFds != null) return
        synchronized(this) {
            if (cachedNa != null && cachedJp != null && cachedFds != null) return
            val json = context.applicationContext.assets.open(ASSET_NAME).use { input ->
                input.bufferedReader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
            }
            val root = JSONObject(json)
            cachedNa = readHashMap(root, "hashes")
            cachedJp = readHashMap(root, "hashes_jp")
            cachedFds = readHashMap(root, "hashes_fds")
        }
    }

    private fun readHashMap(root: JSONObject, field: String): Map<String, String> {
        if (!root.has(field)) return emptyMap()
        val obj = root.getJSONObject(field)
        val out = HashMap<String, String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key.uppercase()] = obj.getString(key)
        }
        return out
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

    private fun extractHeaderlessNes(bytes: ByteArray): ByteArray? {
        if (bytes.size < 16 ||
            bytes[0] != 'N'.code.toByte() ||
            bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'S'.code.toByte() ||
            bytes[3] != 0x1A.toByte()) {
            return null
        }

        val flags6 = bytes[6].toInt() and 0xFF
        val trainerSize = if ((flags6 and 0x04) != 0) 512 else 0
        val offset = 16 + trainerSize
        if (offset >= bytes.size) return null
        return bytes.copyOfRange(offset, bytes.size)
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }
}
