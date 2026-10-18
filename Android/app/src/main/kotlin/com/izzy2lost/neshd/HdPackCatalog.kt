package com.izzy2lost.neshd

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloadable HD packs listed in the NESHD-HDPacks repo (zips live on its GitHub release). */
object HdPackCatalog {
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/izzy2lost/NESHD-HDPacks/main/hdpacks.json"
    private const val CACHE_NAME = "hdpack_catalog.json"

    data class Pack(
        val id: String,
        val title: String,
        val aliases: List<String>,
        val notes: String,
        val url: String,
        val size: Long,
        val sha256: String,
        val romSha1: Set<String>
    )

    enum class Match { ROM_HASH, TITLE, NONE }

    class CancelledException : IOException("Download cancelled")

    /** Fetches the latest catalog, falling back to the last cached copy when offline. */
    fun load(context: Context): List<Pack> {
        val cacheFile = File(context.filesDir, CACHE_NAME)
        try {
            val connection = URL(CATALOG_URL).openConnection().apply {
                connectTimeout = 8000
                readTimeout = 15000
                useCaches = false
            }
            val text = connection.getInputStream().bufferedReader().use { it.readText() }
            val packs = parse(text)
            if (packs.isNotEmpty()) {
                cacheFile.writeText(text)
                return packs
            }
        } catch (ex: Exception) {
            if (!cacheFile.isFile) throw IOException("Could not reach the HD pack catalog", ex)
        }
        return parse(cacheFile.readText())
    }

    /** Orders packs so ones made for this exact ROM come first, then ones whose title matches. */
    fun rank(packs: List<Pack>, romName: String, romSha1: String?): List<Pair<Pack, Match>> {
        val romKeys = CoverNameMatcher.lookupCandidates(romName)
        return packs.map { pack ->
            val match = when {
                romSha1 != null && romSha1 in pack.romSha1 -> Match.ROM_HASH
                (listOf(pack.title) + pack.aliases).any { name ->
                    CoverNameMatcher.indexCandidates(name).any { it in romKeys }
                } -> Match.TITLE
                else -> Match.NONE
            }
            pack to match
        }.sortedWith(compareBy({ it.second.ordinal }, { it.first.title.lowercase() }))
    }

    /** SHA-1 of the whole ROM file, which is what Mesen writes to <supportedRom>. */
    fun romFileSha1(context: Context, pathOrUri: String): String? {
        return try {
            openRom(context, pathOrUri)?.use { hexDigest("SHA-1", it) }
        } catch (_: Exception) {
            null
        }
    }

    /** Downloads [pack] to [dest], verifying its SHA-256. */
    fun download(
        pack: Pack,
        dest: File,
        isCancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val temp = File(dest.parentFile, "${dest.name}.part")
        dest.parentFile?.mkdirs()
        try {
            val connection = URL(pack.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) {
                throw IOException("Server returned HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: pack.size
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReport >= 256 * 1024) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (pack.sha256.isNotBlank() && !actual.equals(pack.sha256, ignoreCase = true)) {
                throw IOException("Download was corrupted (checksum mismatch)")
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
            }
        } finally {
            temp.delete()
        }
    }

    private fun parse(text: String): List<Pack> {
        val items = JSONObject(text).getJSONArray("packs")
        return List(items.length()) { i ->
            val item = items.getJSONObject(i)
            Pack(
                id = item.getString("id"),
                title = item.getString("title"),
                aliases = item.optJSONArray("aliases")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
                notes = item.optString("notes"),
                url = item.getString("url"),
                size = item.optLong("size"),
                sha256 = item.optString("sha256"),
                romSha1 = item.optJSONArray("romSha1")
                    ?.let { a -> (0 until a.length()).map { a.getString(it).uppercase() }.toSet() }
                    .orEmpty()
            )
        }
    }

    private fun openRom(context: Context, pathOrUri: String): InputStream? {
        val uri = Uri.parse(pathOrUri)
        return when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let { File(it).inputStream() }
            else -> File(pathOrUri).inputStream()
        }
    }

    private fun hexDigest(algorithm: String, input: InputStream): String {
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}
