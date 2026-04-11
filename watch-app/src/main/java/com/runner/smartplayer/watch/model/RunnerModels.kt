package com.runner.smartplayer.watch.model

import java.io.File
import java.time.Instant

enum class TrackLabel {
    CALM,
    EXCITED;

    fun value(): String = name.lowercase()

    fun label(): String = when (this) {
        CALM -> "舒缓"
        EXCITED -> "激动"
    }

    companion object {
        fun fromValue(value: String): TrackLabel? =
            entries.firstOrNull { it.value() == value.lowercase() }
    }
}

enum class PlaybackMode {
    CALM,
    EXCITED;

    fun toLabel(): TrackLabel = if (this == EXCITED) TrackLabel.EXCITED else TrackLabel.CALM

    fun value(): String = name.lowercase()
}

enum class QueueMode {
    SHUFFLE,
    LIST_LOOP,
    SINGLE_REPEAT;

    fun next(): QueueMode = when (this) {
        SHUFFLE -> LIST_LOOP
        LIST_LOOP -> SINGLE_REPEAT
        SINGLE_REPEAT -> SHUFFLE
    }

    fun label(): String = when (this) {
        SHUFFLE -> "随机"
        LIST_LOOP -> "循环"
        SINGLE_REPEAT -> "单曲"
    }
}

data class LocalTrack(
    val id: String,
    val relativePath: String,
    val sourceFileName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val bpm: Double?,
    val energyScore: Double?,
    val suggestedLabel: TrackLabel,
    val finalLabel: TrackLabel,
    val sizeBytes: Long,
    val modifiedAt: Instant,
    val file: File,
)

fun LocalTrack.displayTitle(): String {
    val hasMetadata = title.isNotBlank() &&
        artist.isNotBlank() &&
        artist != UNKNOWN_ARTIST
    return if (hasMetadata) {
        "$title - $artist"
    } else {
        sourceFileName.ifBlank { file.name }
    }
}

data class RunnerLibrary(
    val libraryName: String,
    val generatedAt: Instant,
    val tracks: List<LocalTrack>,
)

data class LibrarySummary(
    val libraryName: String = "未导入曲库",
    val totalTracks: Int = 0,
    val calmTracks: Int = 0,
    val excitedTracks: Int = 0,
    val lastImportedAt: Instant? = null,
    val exportDirectory: String = "",
)

enum class SensorAvailability {
    AVAILABLE,
    NO_SENSOR,
    PERMISSION_REQUIRED,
    NO_CONTACT,
    UNRELIABLE,
    STOPPED,
}

data class HeartRateSnapshot(
    val bpm: Int? = null,
    val mode: PlaybackMode = PlaybackMode.CALM,
    val availability: SensorAvailability = SensorAvailability.STOPPED,
    val message: String = "传感器已停止",
)

data class PlaybackSnapshot(
    val currentTrack: LocalTrack? = null,
    val isPlaying: Boolean = false,
    val isAdaptiveEnabled: Boolean = true,
    val isDebugModeEnabled: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.CALM,
    val lastMessage: String = "准备就绪",
    val displayTitle: String = "尚未载入歌曲",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val progressPercent: Float = 0f,
    val volumePercent: Int = 50,
    val queueMode: QueueMode = QueueMode.SHUFFLE,
    val playlistTracks: List<LocalTrack> = emptyList(),
)

data class AppUiState(
    val librarySummary: LibrarySummary = LibrarySummary(),
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val heartRate: HeartRateSnapshot = HeartRateSnapshot(),
)

const val UNKNOWN_ARTIST = "未知歌手"
