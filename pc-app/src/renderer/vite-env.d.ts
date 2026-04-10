/// <reference types="vite/client" />

import type {
  AdbDevice,
  AdbExportLibraryResult,
  AdbPushResult,
  AnalyzedTrack,
  ExportResult,
  RemoteMusicListing,
  ScanProgressUpdate,
  ScanResult,
} from "../common/manifest";

declare global {
  interface Window {
    runnerApp: {
      pickMusicFolder: () => Promise<string | null>;
      pickExportFolder: () => Promise<string | null>;
      scanFolder: (sourceFolder: string) => Promise<ScanResult>;
      onScanProgress: (listener: (progress: ScanProgressUpdate) => void) => () => void;
      exportLibrary: (payload: {
        libraryName: string;
        outputDirectory: string;
        tracks: AnalyzedTrack[];
      }) => Promise<ExportResult>;
      toPreviewUrl: (filePath: string) => Promise<string>;
      listAdbDevices: () => Promise<AdbDevice[]>;
      pushTracksToDevice: (payload: {
        deviceId: string;
        libraryName: string;
        tracks: AnalyzedTrack[];
      }) => Promise<AdbPushResult>;
      pushRunnerExportToDevice: (payload: {
        deviceId: string;
        libraryName: string;
        tracks: AnalyzedTrack[];
      }) => Promise<AdbExportLibraryResult>;
      listRemoteMusic: (payload: { deviceId: string; remotePath: string }) => Promise<RemoteMusicListing>;
      deleteRemoteEntry: (payload: { deviceId: string; remotePath: string }) => Promise<{ ok: true }>;
    };
  }
}

export {};
