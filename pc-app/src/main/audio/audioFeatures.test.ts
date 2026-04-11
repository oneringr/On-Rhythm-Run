import { describe, expect, it } from "vitest";
import {
  applyTrackScoring,
  computeRms,
  computeSpectralCentroid,
  estimateBpm,
} from "./audioFeatures.js";

describe("audio feature helpers", () => {
  it("computes RMS for a constant waveform", () => {
    const samples = new Float32Array([0.5, 0.5, 0.5, 0.5]);
    expect(computeRms(samples)).toBeCloseTo(0.5, 5);
  });

  it("estimates BPM for a synthetic pulse train", () => {
    const sampleRate = 22050;
    const durationSeconds = 12;
    const pulseIntervalSamples = Math.round((sampleRate * 60) / 120);
    const samples = new Float32Array(sampleRate * durationSeconds);

    for (let index = 0; index < samples.length; index += pulseIntervalSamples) {
      samples[index] = 1;
      if (index + 1 < samples.length) samples[index + 1] = 0.6;
      if (index + 2 < samples.length) samples[index + 2] = 0.3;
    }

    expect(estimateBpm(samples, sampleRate)).toBeGreaterThanOrEqual(118);
    expect(estimateBpm(samples, sampleRate)).toBeLessThanOrEqual(122);
  });

  it("labels more energetic tracks as excited", () => {
    const tracks = applyTrackScoring([
      { id: "calm", bpm: 90, rms: 0.1, spectralCentroid: 400 },
      { id: "excited", bpm: 170, rms: 0.45, spectralCentroid: 1800 },
    ]);

    expect(tracks.find((track) => track.id === "calm")?.suggestedLabel).toBe("calm");
    expect(tracks.find((track) => track.id === "excited")?.suggestedLabel).toBe("excited");
  });

  it("computes a higher spectral centroid for brighter signals", () => {
    const sampleRate = 22050;
    const length = 2048;
    const lowTone = new Float32Array(length);
    const highTone = new Float32Array(length);

    for (let index = 0; index < length; index += 1) {
      lowTone[index] = Math.sin((2 * Math.PI * 220 * index) / sampleRate);
      highTone[index] = Math.sin((2 * Math.PI * 1760 * index) / sampleRate);
    }

    const lowCentroid = computeSpectralCentroid(lowTone, sampleRate);
    const highCentroid = computeSpectralCentroid(highTone, sampleRate);

    expect(highCentroid).toBeGreaterThan(lowCentroid);
  });
});
