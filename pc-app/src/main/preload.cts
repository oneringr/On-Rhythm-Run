import type {
  AdbDevice,
  AdbExportLibraryResult,
  AdbPushResult,
  AnalyzedTrack,
  ExportResult,
  PushProgressUpdate,
  RemoteMusicListing,
  ScanProgressUpdate,
  ScanResult,
} from "../common/manifest.js";

const { contextBridge, ipcRenderer } = require("electron") as typeof import("electron");

contextBridge.exposeInMainWorld("runnerApp", {
  pickMusicFolder: (): Promise<string | null> => ipcRenderer.invoke("runner:pick-music-folder"),
  pickExportFolder: (): Promise<string | null> => ipcRenderer.invoke("runner:pick-export-folder"),
  scanFolder: (sourceFolder: string): Promise<ScanResult> =>
    ipcRenderer.invoke("runner:scan-folder", sourceFolder),
  onScanProgress: (listener: (progress: ScanProgressUpdate) => void): (() => void) => {
    const wrappedListener = (_event: Electron.IpcRendererEvent, payload: ScanProgressUpdate) => {
      listener(payload);
    };
    ipcRenderer.on("runner:scan-progress", wrappedListener);
    return () => {
      ipcRenderer.removeListener("runner:scan-progress", wrappedListener);
    };
  },
  onPushProgress: (listener: (progress: PushProgressUpdate) => void): (() => void) => {
    const wrappedListener = (_event: Electron.IpcRendererEvent, payload: PushProgressUpdate) => {
      listener(payload);
    };
    ipcRenderer.on("runner:push-progress", wrappedListener);
    return () => {
      ipcRenderer.removeListener("runner:push-progress", wrappedListener);
    };
  },
  exportLibrary: (payload: {
    libraryName: string;
    outputDirectory: string;
    tracks: AnalyzedTrack[];
  }): Promise<ExportResult> => ipcRenderer.invoke("runner:export-library", payload),
  toPreviewUrl: (filePath: string): Promise<string> => ipcRenderer.invoke("runner:to-preview-url", filePath),
  listAdbDevices: (): Promise<AdbDevice[]> => ipcRenderer.invoke("runner:list-adb-devices"),
  pushTracksToDevice: (payload: {
    deviceId: string;
    libraryName: string;
    tracks: AnalyzedTrack[];
  }): Promise<AdbPushResult> => ipcRenderer.invoke("runner:push-tracks-to-device", payload),
  pushRunnerExportToDevice: (payload: {
    deviceId: string;
    libraryName: string;
    tracks: AnalyzedTrack[];
  }): Promise<AdbExportLibraryResult> => ipcRenderer.invoke("runner:push-runner-export-to-device", payload),
  listRemoteMusic: (payload: {
    deviceId: string;
    remotePath: string;
  }): Promise<RemoteMusicListing> => ipcRenderer.invoke("runner:list-remote-music", payload),
  deleteRemoteEntry: (payload: { deviceId: string; remotePath: string }): Promise<{ ok: true }> =>
    ipcRenderer.invoke("runner:delete-remote-entry", payload),
});
