package com.izzy2lost.neshd

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Unpacks ROMs from zipped games once, at scan time, so the library lists them as plain
 * files and the core never has to open an archive on launch.
 */
object ZipRomExtractor {
    private const val ROOT_FOLDER = "extracted_roms"
    private const val STAMP_NAME = ".source"
    // Bump when extracted file naming changes so existing extractions are redone.
    private const val STAMP_VERSION = 2
    // Larger than any real NES/FDS image; guards against zip bombs.
    private const val MAX_ROM_BYTES = 16L shl 20

    class Result(val dirName: String, val roms: List<File>)

    /** Extracts [zip] into app storage, reusing the previous extraction while the zip is unchanged. */
    fun extractCached(context: Context, zip: DocumentFile, isRomName: (String) -> Boolean): Result {
        val dirName = hashName(zip.uri.toString())
        val dir = File(root(context), dirName)
        val stamp = "$STAMP_VERSION:${zip.length()}:${zip.lastModified()}"
        val stampFile = File(dir, STAMP_NAME)

        if (stampFile.isFile && stampFile.readText() == stamp) {
            val cached = dir.listFiles { f -> f.isFile && f.name != STAMP_NAME }.orEmpty()
            if (cached.isNotEmpty()) return Result(dirName, cached.sortedBy { it.name })
        }

        val temp = File(root(context), "$dirName.tmp")
        temp.deleteRecursively()
        return try {
            val roms = extract(context, zip.uri, temp, isRomName)
            dir.deleteRecursively()
            if (roms.isEmpty()) {
                temp.deleteRecursively()
                return Result(dirName, emptyList())
            }
            File(temp, STAMP_NAME).writeText(stamp)
            if (!temp.renameTo(dir)) throw IOException("Could not store extracted ROM")
            Result(dirName, roms.map { File(dir, it.name) })
        } catch (ex: Exception) {
            temp.deleteRecursively()
            throw ex
        }
    }

    /** Extracts every ROM in the zip at [uri] into [destDir] and returns them. */
    fun extract(context: Context, uri: Uri, destDir: File, isRomName: (String) -> Boolean): List<File> {
        destDir.mkdirs()
        val out = mutableListOf<File>()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open zip")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val path = entry.name.replace('\\', '/')
                // Flatten to the file name so entries can never escape destDir.
                // Same rule as MainActivity.sanitizeFileName: the core looks up HD packs by
                // this file name, and the app installs them under the sanitized ROM name.
                val name = path.substringAfterLast('/').replace(Regex("""[^\w. -]"""), "_")
                if (!entry.isDirectory && isRomName(name) &&
                    !path.contains("__MACOSX/") && !name.startsWith("._")) {
                    val target = File(destDir, name)
                    if (target.exists()) {
                        // Keep the first copy if a zip holds two ROMs with the same name.
                    } else {
                        copyLimited(zip, target)
                        out += target
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** Deletes extractions whose zip is no longer in the scanned folder. */
    fun prune(context: Context, keep: Set<String>) {
        root(context).listFiles()?.forEach { dir ->
            if (dir.name !in keep) dir.deleteRecursively()
        }
    }

    private fun copyLimited(zip: ZipInputStream, target: File) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            target.outputStream().use { output ->
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ROM_BYTES) throw IOException("ROM in zip is too large")
                    output.write(buffer, 0, read)
                }
            }
        } catch (ex: IOException) {
            target.delete()
            throw ex
        }
    }

    private fun root(context: Context) = File(context.filesDir, ROOT_FOLDER).also { it.mkdirs() }

    private fun hashName(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
}
