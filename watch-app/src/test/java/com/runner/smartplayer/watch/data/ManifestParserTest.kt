package com.runner.smartplayer.watch.data

import com.runner.smartplayer.watch.model.TrackLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManifestParserTest {
    @Test
    fun parsesValidManifestAndSkipsMissingFiles() {
        val tempRoot = Files.createTempDirectory("runner-manifest-test").toFile()
        val tracksDir = File(tempRoot, "tracks").apply { mkdirs() }
        File(tracksDir, "present.mp3").writeText("stub")

        val manifest = """
            {
              "schemaVersion": 1,
              "libraryName": "Test Library",
              "generatedAt": "2026-04-10T12:00:00Z",
              "tracks": [
                {
                  "id": "present",
                  "relativePath": "tracks/present.mp3",
                  "sourceFileName": "present.mp3",
                  "durationMs": 1000,
                  "labels": {
                    "suggested": "calm",
                    "final": "calm"
                  },
                  "suggestedLabel": "calm",
                  "finalLabel": "calm",
                  "sizeBytes": 4,
                  "modifiedAt": "2026-04-10T12:00:00Z"
                },
                {
                  "id": "missing",
                  "relativePath": "tracks/missing.mp3",
                  "sourceFileName": "missing.mp3",
                  "durationMs": 1000,
                  "suggestedLabel": "excited",
                  "finalLabel": "excited",
                  "sizeBytes": 4,
                  "modifiedAt": "2026-04-10T12:00:00Z"
                }
              ]
            }
        """.trimIndent()

        val parsed = ManifestParser.parse(tempRoot, manifest)
        assertEquals("Test Library", parsed.libraryName)
        assertEquals(1, parsed.tracks.size)
        assertEquals(TrackLabel.CALM, parsed.tracks.first().finalLabel)
        assertTrue(parsed.tracks.first().file.exists())
    }
}
