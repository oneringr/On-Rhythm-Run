import type { AnalyzedTrack } from "../../common/manifest.js";

export interface ExtractedAudioFeatures {
  bpm: number;
  rms: number;
  spectralCentroid: number;
}

export function computeRms(samples: Float32Array): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (let index = 0; index < samples.length; index += 1) {
    const sample = samples[index] ?? 0;
    sum += sample * sample;
  }
  return Math.sqrt(sum / samples.length);
}

export function computeSpectralCentroid(
  samples: Float32Array,
  sampleRate: number,
  windowSize = 2048,
): number {
  if (samples.length === 0) return 0;
  const analysisWindow = sliceAnalysisWindow(samples, windowSize);
  const totalBins = Math.floor(analysisWindow.length / 2);
  let magnitudeSum = 0;
  let weightedSum = 0;

  for (let bin = 0; bin < totalBins; bin += 1) {
    let real = 0;
    let imaginary = 0;
    for (let sampleIndex = 0; sampleIndex < analysisWindow.length; sampleIndex += 1) {
      const phase = (2 * Math.PI * bin * sampleIndex) / analysisWindow.length;
      const sample = analysisWindow[sampleIndex] ?? 0;
      real += sample * Math.cos(phase);
      imaginary -= sample * Math.sin(phase);
    }

    const magnitude = Math.sqrt(real * real + imaginary * imaginary);
    const frequency = (bin * sampleRate) / analysisWindow.length;
    magnitudeSum += magnitude;
    weightedSum += magnitude * frequency;
  }

  return magnitudeSum === 0 ? 0 : weightedSum / magnitudeSum;
}

export function estimateBpm(
  samples: Float32Array,
  sampleRate: number,
  frameSize = 1024,
  hopSize = 512,
): number {
  if (samples.length < frameSize) return 0;

  const envelope: number[] = [];
  let previousEnergy = 0;
  for (let offset = 0; offset + frameSize < samples.length; offset += hopSize) {
    let energy = 0;
    for (let index = 0; index < frameSize; index += 1) {
      energy += Math.abs(samples[offset + index] ?? 0);
    }
    energy /= frameSize;
    envelope.push(Math.max(0, energy - previousEnergy));
    previousEnergy = energy;
  }

  const frameRate = sampleRate / hopSize;
  let bestBpm = 120;
  let bestScore = Number.NEGATIVE_INFINITY;

  for (let bpm = 60; bpm <= 220; bpm += 1) {
    const lag = Math.round((frameRate * 60) / bpm);
    if (lag <= 0 || lag >= envelope.length) continue;

    let score = 0;
    for (let index = lag; index < envelope.length; index += 1) {
      score += envelope[index] * envelope[index - lag];
    }

    if (score > bestScore) {
      bestScore = score;
      bestBpm = bpm;
    }
  }

  while (bestBpm < 90 && bestBpm * 2 <= 220) {
    bestBpm *= 2;
  }

  return bestBpm;
}

export function applyTrackScoring<
  T extends { bpm: number; rms: number; spectralCentroid: number; id: string; }
>(tracks: T[]): Array<T & Pick<AnalyzedTrack, "energyScore" | "suggestedLabel" | "finalLabel">> {
  const bpmStats = stats(tracks.map((track) => track.bpm));
  const rmsStats = stats(tracks.map((track) => track.rms));
  const centroidStats = stats(tracks.map((track) => track.spectralCentroid));

  return tracks.map((track) => {
    const bpmZ = zScore(track.bpm, bpmStats.mean, bpmStats.stdDev);
    const rmsZ = zScore(track.rms, rmsStats.mean, rmsStats.stdDev);
    const centroidZ = zScore(track.spectralCentroid, centroidStats.mean, centroidStats.stdDev);
    const energyScore = Number((0.5 * bpmZ + 0.35 * rmsZ + 0.15 * centroidZ).toFixed(4));
    const suggestedLabel = energyScore >= 0 ? "excited" : "calm";
    return {
      ...track,
      energyScore,
      suggestedLabel,
      finalLabel: suggestedLabel,
    };
  });
}

function sliceAnalysisWindow(samples: Float32Array, windowSize: number): Float32Array {
  if (samples.length <= windowSize) return samples;
  const middle = Math.floor(samples.length / 2);
  const start = Math.max(0, middle - Math.floor(windowSize / 2));
  return samples.slice(start, start + windowSize);
}

function stats(values: number[]): { mean: number; stdDev: number } {
  if (values.length === 0) {
    return { mean: 0, stdDev: 1 };
  }
  const mean = values.reduce((sum, value) => sum + value, 0) / values.length;
  const variance =
    values.reduce((sum, value) => sum + (value - mean) * (value - mean), 0) / values.length;
  return { mean, stdDev: Math.sqrt(variance) || 1 };
}

function zScore(value: number, mean: number, stdDev: number): number {
  return (value - mean) / stdDev;
}
