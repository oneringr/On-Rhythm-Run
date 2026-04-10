import { afterEach, describe, expect, it } from "vitest";
import { promises as fs } from "node:fs";
import path from "node:path";
import os from "node:os";
import { ExportBuilder } from "./exportBuilder.js";
import type { AnalyzedTrack } from "../../common/manifest.js";

describe("ExportBuilder", () => {
  const cleanupPaths: string[] = [];

  afterEach(async () => {
    await Promise.all(
      cleanupPaths.splice(0).map((directory) => fs.rm(directory, { recursive: true, force: true })),
    );
  });

  it("writes manifest and copies tracks into RunnerPlayerExport", async () => {
    const sourceRoot = await fs.mkdtemp(path.join(os.tmpdir(), "runner-source-"));
    const outputRoot = await fs.mkdtemp(path.join(os.tmpdir(), "runner-output-"));
    cleanupPaths.push(sourceRoot, outputRoot);

    const sourceTrackPath = path.join(sourceRoot, "tempo.mp3");
    await fs.writeFile(sourceTrackPath, "stub mp3");

    const builder = new ExportBuilder();
    const result = await builder.exportLibrary({
      libraryName: "Tempo Pack",
      outputDirectory: outputRoot,
      tracks: [makeTrack(sourceTrackPath)],
    });

    const manifestRaw = await fs.readFile(result.manifestPath, "utf8");
    const manifest = JSON.parse(manifestRaw) as {
      libraryName: string;
      tracks: Array<{
        relativePath: string;
        finalLabel: string;
        sourceFileName: string;
        labels?: { suggested: string; final: string };
      }>;
    };

    expect(result.trackCount).toBe(1);
    expect(manifest.libraryName).toBe("Tempo Pack");
    expect(manifest.tracks[0]?.finalLabel).toBe("excited");
    expect(manifest.tracks[0]?.sourceFileName).toBe("tempo.mp3");
    expect(manifest.tracks[0]?.relativePath).toBe("tracks/tempo.mp3");
    expect(manifest.tracks[0]?.labels).toEqual({
      suggested: "excited",
      final: "excited",
    });
    await expect(fs.stat(path.join(result.outputRoot, manifest.tracks[0]!.relativePath))).resolves.toBeTruthy();
  });

  it("skips tracks excluded from export", async () => {
    const sourceRoot = await fs.mkdtemp(path.join(os.tmpdir(), "runner-source-"));
    const outputRoot = await fs.mkdtemp(path.join(os.tmpdir(), "runner-output-"));
    cleanupPaths.push(sourceRoot, outputRoot);

    const includedTrackPath = path.join(sourceRoot, "included.mp3");
    const excludedTrackPath = path.join(sourceRoot, "excluded.mp3");
    await fs.writeFile(includedTrackPath, "included");
    await fs.writeFile(excludedTrackPath, "excluded");

    const builder = new ExportBuilder();
    const result = await builder.exportLibrary({
      libraryName: "Tempo Pack",
      outputDirectory: outputRoot,
      tracks: [
        makeTrack(includedTrackPath),
        { ...makeTrack(excludedTrackPath), id: "excluded", sourceFileName: "excluded.mp3", excludedFromExport: true },
      ],
    });

    const manifestRaw = await fs.readFile(result.manifestPath, "utf8");
    const manifest = JSON.parse(manifestRaw) as {
      tracks: Array<{ sourceFileName: string }>;
    };

    expect(result.trackCount).toBe(1);
    expect(manifest.tracks.map((track) => track.sourceFileName)).toEqual(["tempo.mp3"]);
  });
});

function makeTrack(sourcePath: string): AnalyzedTrack {
  return {
    id: "abc123",
    sourcePath,
    sourceFileName: "tempo.mp3",
    title: "Tempo",
    artist: "Runner",
    durationMs: 180000,
    sizeBytes: 8,
    modifiedAt: "2026-04-10T12:00:00Z",
    bpm: 162,
    rms: 0.33,
    spectralCentroid: 1500,
    energyScore: 1.2,
    suggestedLabel: "excited",
    finalLabel: "excited",
  };
}
