import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createRequire } from "node:module";
import type { AnalyzedTrack, RawScannedTrack, ScanProgressUpdate } from "../../common/manifest.js";
import {
  applyTrackScoring,
  computeRms,
  computeSpectralCentroid,
  estimateBpm,
} from "./audioFeatures.js";

const SAMPLE_RATE = 22_050;
const require = createRequire(import.meta.url);
const ffmpegPath = require("ffmpeg-static") as string | null;

export class AudioAnalyzerWorker {
  async analyze(
    tracks: RawScannedTrack[],
    onProgress?: (progress: ScanProgressUpdate) => void,
  ): Promise<AnalyzedTrack[]> {
    const withFeatures = [];
    for (const [index, track] of tracks.entries()) {
      const samples = await decodeToMonoSamples(track.sourcePath);
      withFeatures.push({
        ...track,
        bpm: estimateBpm(samples, SAMPLE_RATE),
        rms: Number(computeRms(samples).toFixed(6)),
        spectralCentroid: Number(computeSpectralCentroid(samples, SAMPLE_RATE).toFixed(3)),
      });

      onProgress?.({
        phase: "analyzing",
        processed: index + 1,
        total: tracks.length,
        percent: tracks.length === 0 ? 100 : 35 + Math.round(((index + 1) / tracks.length) * 65),
        message: `正在分析音频特征 ${index + 1}/${tracks.length}`,
      });
    }
    return applyTrackScoring(withFeatures);
  }
}

async function decodeToMonoSamples(filePath: string): Promise<Float32Array> {
  const binaryPath = ffmpegPath ?? undefined;
  if (!binaryPath) {
    throw new Error("未找到 ffmpeg-static，可执行文件不可用。");
  }

  const pcmBuffer = await new Promise<Buffer>((resolve, reject) => {
    const process: ChildProcessWithoutNullStreams = spawn(binaryPath, [
      "-v",
      "error",
      "-t",
      "90",
      "-i",
      filePath,
      "-ac",
      "1",
      "-ar",
      String(SAMPLE_RATE),
      "-f",
      "s16le",
      "-",
    ]);

    const stdoutChunks: Buffer[] = [];
    const stderrChunks: Buffer[] = [];

    process.stdout.on("data", (chunk: Buffer) => stdoutChunks.push(Buffer.from(chunk)));
    process.stderr.on("data", (chunk: Buffer) => stderrChunks.push(Buffer.from(chunk)));
    process.on("error", reject);
    process.on("close", (code: number | null) => {
      if (code === 0) {
        resolve(Buffer.concat(stdoutChunks));
      } else {
        reject(new Error(Buffer.concat(stderrChunks).toString("utf8") || `ffmpeg exited with ${code}`));
      }
    });
  });

  const totalSamples = Math.floor(pcmBuffer.length / 2);
  const samples = new Float32Array(totalSamples);
  for (let index = 0; index < totalSamples; index += 1) {
    samples[index] = pcmBuffer.readInt16LE(index * 2) / 32768;
  }
  return samples;
}
