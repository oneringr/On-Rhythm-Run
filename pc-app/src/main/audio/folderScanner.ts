import { createHash } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { parseFile } from "music-metadata";
import type { RawScannedTrack, ScanProgressUpdate } from "../../common/manifest.js";

const AUDIO_FILE_EXTENSIONS = new Set([
  ".aac",
  ".aiff",
  ".alac",
  ".ape",
  ".flac",
  ".m4a",
  ".mp3",
  ".ogg",
  ".opus",
  ".wav",
  ".wma",
]);

interface FolderScanDetails {
  tracks: RawScannedTrack[];
  otherAudioExtensions: string[];
}

export class FolderScanner {
  async scan(
    sourceFolder: string,
    onProgress?: (progress: ScanProgressUpdate) => void,
  ): Promise<FolderScanDetails> {
    const collected = await collectAudioFiles(sourceFolder);
    const tracks: RawScannedTrack[] = [];
    const totalTracks = collected.mp3Files.length;

    for (const [index, filePath] of collected.mp3Files.entries()) {
      const stats = await fs.stat(filePath);
      const metadata = await parseFile(filePath, { duration: true });
      const relativeSource = path.relative(sourceFolder, filePath);
      const id = createHash("sha1")
        .update(`${relativeSource}|${stats.size}|${stats.mtimeMs}`)
        .digest("hex");

      tracks.push({
        id,
        sourcePath: filePath,
        sourceFileName: path.basename(filePath),
        title: metadata.common.title ?? path.basename(filePath, path.extname(filePath)),
        artist: metadata.common.artist ?? "未知歌手",
        durationMs: Math.max(1, Math.round((metadata.format.duration ?? 0) * 1000)),
        sizeBytes: stats.size,
        modifiedAt: new Date(stats.mtimeMs).toISOString(),
      });

      onProgress?.({
        phase: "collecting",
        processed: index + 1,
        total: totalTracks,
        percent: totalTracks === 0 ? 35 : Math.round(((index + 1) / totalTracks) * 35),
        message: `正在读取歌曲信息 ${index + 1}/${totalTracks}`,
      });
    }

    return {
      tracks: tracks.sort((left, right) => left.sourceFileName.localeCompare(right.sourceFileName)),
      otherAudioExtensions: Array.from(collected.otherAudioExtensions).sort(),
    };
  }
}

async function collectAudioFiles(directory: string): Promise<{
  mp3Files: string[];
  otherAudioExtensions: Set<string>;
}> {
  const entries = await fs.readdir(directory, { withFileTypes: true });
  const currentLevelResults = await Promise.all(
    entries.map(async (entry) => {
      const fullPath = path.join(directory, entry.name);
      if (!entry.isFile()) {
        return {
          mp3Files: [],
          otherAudioExtensions: new Set<string>(),
        };
      }

      const extension = path.extname(entry.name).toLowerCase();
      if (extension === ".mp3") {
        return {
          mp3Files: [fullPath],
          otherAudioExtensions: new Set<string>(),
        };
      }

      if (AUDIO_FILE_EXTENSIONS.has(extension)) {
        return {
          mp3Files: [],
          otherAudioExtensions: new Set<string>([extension]),
        };
      }

      return {
        mp3Files: [],
        otherAudioExtensions: new Set<string>(),
      };
    }),
  );

  const mp3Files: string[] = [];
  const otherAudioExtensions = new Set<string>();

  for (const result of currentLevelResults) {
    mp3Files.push(...result.mp3Files);
    for (const extension of result.otherAudioExtensions) {
      otherAudioExtensions.add(extension);
    }
  }

  return {
    mp3Files,
    otherAudioExtensions,
  };
}
