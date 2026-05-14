package com.izzy2lost.neshd

import android.content.Context
import java.io.File

object AppStorage {
    private const val HOME_FOLDER = "NesHdHome"

    private val runtimeFolders = arrayOf(
        "Saves",
        "SaveStates",
        "HdPacks",
        "Firmware",
        "Screenshots",
        "RecentGames"
    )

    fun nesHdHomeDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val homeDir = File(baseDir, HOME_FOLDER).also { it.mkdirs() }
        migrateInternalHomeIfNeeded(context, homeDir)
        ensureRuntimeFolders(homeDir)
        return homeDir
    }

    fun hdPackDir(context: Context, name: String): File {
        return File(nesHdHomeDir(context), "HdPacks/$name")
    }

    private fun ensureRuntimeFolders(homeDir: File) {
        runtimeFolders.forEach { folder ->
            File(homeDir, folder).mkdirs()
        }
    }

    private fun migrateInternalHomeIfNeeded(context: Context, targetHome: File) {
        val internalHome = File(context.filesDir, HOME_FOLDER)
        if (!internalHome.isDirectory || internalHome.absolutePath == targetHome.absolutePath) {
            return
        }

        copyMissingChildren(internalHome, targetHome)
    }

    private fun copyMissingChildren(sourceDir: File, targetDir: File) {
        sourceDir.listFiles()?.forEach { source ->
            val target = File(targetDir, source.name)
            if (source.isDirectory) {
                target.mkdirs()
                copyMissingChildren(source, target)
            } else if (!target.exists()) {
                source.copyTo(target, overwrite = false)
            }
        }
    }
}
