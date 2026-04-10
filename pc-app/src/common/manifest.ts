export type TrackLabel = "calm" | "excited";

export interface RawScannedTrack {
  id: string;
  sourcePath: string;
  sourceFileName: string;
  title: string;
  artist: string;
  durationMs: number;
  sizeBytes: number;
  modifiedAt: string;
}

export interface AnalyzedTrack extends RawScannedTrack {
  bpm: number;
  rms: number;
  spectralCentroid: number;
  energyScore: number;
  suggestedLabel: TrackLabel;
  finalLabel: TrackLabel;
  excludedFromExport?: boolean;
}

export interface RunnerManifestTrack {
  id: string;
  relativePath: string;
  sourceFileName: string;
  title: string;
  artist: string;
  durationMs: number;
  bpm: number;
  energyScore: number;
  suggestedLabel: TrackLabel;
  finalLabel: TrackLabel;
  labels?: {
    suggested: TrackLabel;
    final: TrackLabel;
  };
  sizeBytes: number;
  modifiedAt: string;
}

export interface RunnerManifest {
  schemaVersion: 1;
  libraryName: string;
  generatedAt: string;
  tracks: RunnerManifestTrack[];
}

export interface ScanResult {
  libraryName: string;
  tracks: AnalyzedTrack[];
  otherAudioExtensions: string[];
}

export interface ScanProgressUpdate {
  phase: "collecting" | "analyzing" | "done";
  processed: number;
  total: number;
  percent: number;
  message: string;
}

export interface ExportResult {
  outputRoot: string;
  manifestPath: string;
  trackCount: number;
}

export interface AdbDevice {
  id: string;
  state: string;
  model?: string;
  product?: string;
  deviceName?: string;
}

export interface RemoteMusicEntry {
  name: string;
  path: string;
  isDirectory: boolean;
}

export interface RemoteMusicListing {
  currentPath: string;
  parentPath: string | null;
  entries: RemoteMusicEntry[];
  stats: {
    directoryCount: number;
    fileCount: number;
    mp3Count: number;
    recursiveMp3Count: number;
  };
}

export interface AdbPushResult {
  deviceId: string;
  remotePath: string;
  pushedCount: number;
}

export interface AdbExportLibraryResult {
  deviceId: string;
  remotePath: string;
  manifestPath: string;
  trackCount: number;
}

export function makeRelativeTrackPath(sourceFileName: string): string {
  const sanitizedFileName = sourceFileName.replace(/[\\/]/g, "_");
  return `tracks/${sanitizedFileName}`;
}
