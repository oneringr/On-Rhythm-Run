package com.runner.smartplayer.watch.data

import android.os.Environment
import java.io.File

sealed class LibrarySource {
    data class ManifestExportRoot(val exportRoot: File) : LibrarySource()
    data class PlainMusicDirectory(val directory: File) : LibrarySource()
}

class LibraryScanner(
) {
    fun exportRoot(): File = File(publicMusicRoot(), "RunnerPlayerExport")

    fun manifestFile(): File = File(exportRoot(), "runner_manifest.json")

    fun tracksDirectory(): File = File(exportRoot(), "tracks")

    fun findLibrarySource(): LibrarySource? {
        findManifestExportRoot()?.let { return LibrarySource.ManifestExportRoot(it) }
        findPlainMusicDirectory()?.let { return LibrarySource.PlainMusicDirectory(it) }
        return null
    }

    fun candidateSearchPaths(): List<String> {
        val paths = linkedSetOf<String>()
        val musicRoot = publicMusicRoot()
        paths += musicRoot.absolutePath
        paths += exportRoot().absolutePath
        childDirectories(musicRoot).forEach { child ->
            paths += child.absolutePath
            paths += File(child, "RunnerPlayerExport").absolutePath
        }
        return paths.toList()
    }

    private fun findManifestExportRoot(): File? {
        val candidates = buildList {
            val musicRoot = publicMusicRoot()
            add(File(musicRoot, "RunnerPlayerExport"))
            addAll(childDirectories(musicRoot))
        }

        for (candidate in candidates) {
            if (!candidate.exists() || !candidate.isDirectory) {
                continue
            }

            val directManifest = File(candidate, "runner_manifest.json")
            if (directManifest.isFile) {
                return candidate
            }

            val nestedExportRoot = File(candidate, "RunnerPlayerExport")
            if (File(nestedExportRoot, "runner_manifest.json").isFile) {
                return nestedExportRoot
            }
        }

        return null
    }

    private fun findPlainMusicDirectory(): File? {
        val candidates = buildList {
            add(publicMusicRoot())
            addAll(childDirectories(publicMusicRoot()))
        }

        return candidates
            .filter { directory ->
                directory.isDirectory &&
                    File(directory, "runner_manifest.json").exists().not() &&
                    containsMp3Files(directory)
            }
            .maxByOrNull { directory -> directory.lastModified() }
    }

    private fun containsMp3Files(directory: File): Boolean {
        return directory.listFiles()
            ?.any { file -> file.isFile && file.extension.equals("mp3", ignoreCase = true) }
            ?: false
    }

    private fun publicMusicRoot(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            ?: File("/sdcard/Music")
    }

    private fun childDirectories(parent: File): List<File> {
        return parent.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }
}
