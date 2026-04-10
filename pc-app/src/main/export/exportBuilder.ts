import { promises as fs } from "node:fs";
import path from "node:path";
import type { AnalyzedTrack, ExportResult, RunnerManifest } from "../../common/manifest.js";
import { makeRelativeTrackPath } from "../../common/manifest.js";

export class ExportBuilder {
  async exportLibrary(params: {
    libraryName: string;
    outputDirectory: string;
    tracks: AnalyzedTrack[];
  }): Promise<ExportResult> {
    const tracksToExport = params.tracks.filter((track) => !track.excludedFromExport);
    if (tracksToExport.length === 0) {
      throw new Error("当前没有可导出的歌曲，请先恢复至少一首歌曲。");
    }

    const outputRoot = path.join(params.outputDirectory, "RunnerPlayerExport");
    const tracksDirectory = path.join(outputRoot, "tracks");

    await fs.rm(outputRoot, { recursive: true, force: true });
    await fs.mkdir(tracksDirectory, { recursive: true });

    const manifest: RunnerManifest = {
      schemaVersion: 1,
      libraryName: params.libraryName.trim() || "跑步歌单",
      generatedAt: new Date().toISOString(),
      tracks: [],
    };

    for (const track of tracksToExport) {
      const relativePath = makeRelativeTrackPath(track.sourceFileName);
      await fs.copyFile(track.sourcePath, path.join(outputRoot, relativePath));
      manifest.tracks.push({
        id: track.id,
        relativePath,
        sourceFileName: track.sourceFileName,
        title: track.title,
        artist: track.artist,
        durationMs: track.durationMs,
        bpm: track.bpm,
        energyScore: track.energyScore,
        suggestedLabel: track.suggestedLabel,
        finalLabel: track.finalLabel,
        labels: {
          suggested: track.suggestedLabel,
          final: track.finalLabel,
        },
        sizeBytes: track.sizeBytes,
        modifiedAt: track.modifiedAt,
      });
    }

    const manifestPath = path.join(outputRoot, "runner_manifest.json");
    await fs.writeFile(manifestPath, JSON.stringify(manifest, null, 2), "utf8");

    return {
      outputRoot,
      manifestPath,
      trackCount: manifest.tracks.length,
    };
  }
}
