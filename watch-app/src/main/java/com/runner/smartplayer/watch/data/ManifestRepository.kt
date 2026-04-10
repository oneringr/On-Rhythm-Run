package com.runner.smartplayer.watch.data

import com.runner.smartplayer.watch.model.LibrarySummary
import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.RunnerLibrary
import com.runner.smartplayer.watch.model.TrackLabel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

class ManifestRepository(
    private val libraryScanner: LibraryScanner,
) {
    fun loadLibrary(): ManifestLoadResult {
        return when (val source = libraryScanner.findLibrarySource()) {
            is LibrarySource.ManifestExportRoot -> loadManifestLibrary(source.exportRoot)
            is LibrarySource.PlainMusicDirectory -> loadPlainMusicDirectory(source.directory)
            null -> {
                val searchPaths = libraryScanner.candidateSearchPaths().joinToString("\n")
                ManifestLoadResult.Error(
                    "未找到可用曲库，请将 RunnerPlayerExport 复制到 /sdcard/Music，或把 MP3 放进 /sdcard/Music（根目录或子文件夹）。\n已检查路径：\n$searchPaths"
                )
            }
        }
    }

    private fun loadManifestLibrary(exportRoot: File): ManifestLoadResult {
        val manifestFile = File(exportRoot, "runner_manifest.json")
        return try {
            val raw = manifestFile.readText()
            val library = ManifestParser.parse(
                rootDirectory = exportRoot,
                manifestContents = raw,
            )
            ManifestLoadResult.Success(
                library = library,
                summary = library.toSummary(exportRoot.absolutePath),
                statusMessage = "曲库已加载",
            )
        } catch (error: Exception) {
            ManifestLoadResult.Error(error.message ?: "曲库加载失败")
        }
    }

    private fun loadPlainMusicDirectory(directory: File): ManifestLoadResult {
        return try {
            val mp3Files = directory.listFiles()
                ?.filter { file -> file.isFile && file.extension.equals("mp3", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()

            require(mp3Files.isNotEmpty()) { "目录中没有可播放的 MP3 文件：${directory.absolutePath}" }

            val tracks = mp3Files.map { file ->
                LocalTrack(
                    id = fallbackTrackId(file),
                    relativePath = file.name,
                    sourceFileName = file.name,
                    title = file.nameWithoutExtension,
                    artist = "未知歌手",
                    durationMs = 0L,
                    bpm = null,
                    energyScore = null,
                    suggestedLabel = TrackLabel.CALM,
                    finalLabel = TrackLabel.CALM,
                    sizeBytes = file.length(),
                    modifiedAt = Instant.ofEpochMilli(file.lastModified()),
                    file = file,
                )
            }

            val library = RunnerLibrary(
                libraryName = directory.name.ifBlank { "Music 曲库" },
                generatedAt = tracks.maxOfOrNull { it.modifiedAt } ?: Instant.now(),
                tracks = tracks,
            )

            ManifestLoadResult.Success(
                library = library,
                summary = library.toSummary(directory.absolutePath),
                statusMessage = "已载入普通曲库，未找到标签文件，当前默认按舒缓模式处理",
            )
        } catch (error: Exception) {
            ManifestLoadResult.Error(error.message ?: "普通曲库加载失败")
        }
    }

    private fun RunnerLibrary.toSummary(path: String): LibrarySummary {
        val calmCount = tracks.count { it.finalLabel == TrackLabel.CALM }
        val excitedCount = tracks.count { it.finalLabel == TrackLabel.EXCITED }
        return LibrarySummary(
            libraryName = libraryName,
            totalTracks = tracks.size,
            calmTracks = calmCount,
            excitedTracks = excitedCount,
            lastImportedAt = generatedAt,
            exportDirectory = path,
        )
    }

    private fun fallbackTrackId(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val seed = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        return digest.digest(seed.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

sealed class ManifestLoadResult {
    data class Success(
        val library: RunnerLibrary,
        val summary: LibrarySummary,
        val statusMessage: String,
    ) : ManifestLoadResult()

    data class Error(
        val message: String,
    ) : ManifestLoadResult()
}

object ManifestParser {
    fun parse(rootDirectory: File, manifestContents: String): RunnerLibrary {
        val root = JSONObject(manifestContents)
        val schemaVersion = root.optInt("schemaVersion", -1)
        require(schemaVersion == 1) { "不支持的 schemaVersion：$schemaVersion" }

        val generatedAt = Instant.parse(root.getString("generatedAt"))
        val tracks = parseTracks(root.getJSONArray("tracks"), rootDirectory)
        require(tracks.isNotEmpty()) { "曲库清单中没有可用歌曲" }

        return RunnerLibrary(
            libraryName = root.getString("libraryName"),
            generatedAt = generatedAt,
            tracks = tracks,
        )
    }

    private fun parseTracks(tracksArray: JSONArray, rootDirectory: File): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()
        for (index in 0 until tracksArray.length()) {
            val entry = tracksArray.optJSONObject(index) ?: continue
            val relativePath = entry.optString("relativePath")
            val finalLabel = parseLabel(entry, "final") ?: continue
            val suggestedLabel = parseLabel(entry, "suggested") ?: continue
            val file = File(rootDirectory, relativePath)
            if (!file.exists() || !file.isFile) {
                continue
            }

            tracks += LocalTrack(
                id = entry.getString("id"),
                relativePath = relativePath,
                sourceFileName = entry.optString("sourceFileName", file.name),
                title = entry.optString("title", file.nameWithoutExtension),
                artist = entry.optString("artist", "未知歌手"),
                durationMs = entry.optLong("durationMs"),
                bpm = entry.optDouble("bpm").takeUnless { it.isNaN() },
                energyScore = entry.optDouble("energyScore").takeUnless { it.isNaN() },
                suggestedLabel = suggestedLabel,
                finalLabel = finalLabel,
                sizeBytes = entry.optLong("sizeBytes", file.length()),
                modifiedAt = Instant.parse(entry.getString("modifiedAt")),
                file = file,
            )
        }
        return tracks
    }

    private fun parseLabel(entry: JSONObject, key: String): TrackLabel? {
        val labelsObject = entry.optJSONObject("labels")
        val nestedValue = labelsObject?.optString(key).orEmpty()
        val legacyKey = "${key}Label"
        val legacyValue = entry.optString(legacyKey)

        return TrackLabel.fromValue(nestedValue)
            ?: TrackLabel.fromValue(legacyValue)
    }
}
