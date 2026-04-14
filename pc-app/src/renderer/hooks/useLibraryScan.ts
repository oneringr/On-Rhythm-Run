import { useCallback, useEffect, useMemo, useState } from "react";
import type { AnalyzedTrack, ScanProgressUpdate } from "../../common/manifest";
import {
  DEFAULT_LIBRARY_NAME,
  FALLBACK_LIBRARY_NAME,
  INITIAL_SCAN_STATUS,
} from "../config";
import { buildScanStatus, toErrorMessage } from "../utils/formatting";

export type LibraryStats = {
  total: number;
  exportable: number;
  excluded: number;
  calm: number;
  excited: number;
};

export function useLibraryScan() {
  const [libraryName, setLibraryName] = useState(DEFAULT_LIBRARY_NAME);
  const [sourceFolder, setSourceFolder] = useState("");
  const [exportFolder, setExportFolder] = useState("");
  const [tracks, setTracks] = useState<AnalyzedTrack[]>([]);
  const [status, setStatus] = useState(INITIAL_SCAN_STATUS);
  const [isScanning, setIsScanning] = useState(false);
  const [scanProgress, setScanProgress] = useState<ScanProgressUpdate | null>(null);

  const exportableTracks = useMemo(
    () => tracks.filter((track) => !track.excludedFromExport),
    [tracks],
  );

  const stats = useMemo<LibraryStats>(() => {
    const calm = exportableTracks.filter((track) => track.finalLabel === "calm").length;
    const excited = exportableTracks.filter((track) => track.finalLabel === "excited").length;
    return {
      total: tracks.length,
      exportable: exportableTracks.length,
      excluded: tracks.length - exportableTracks.length,
      calm,
      excited,
    };
  }, [exportableTracks, tracks.length]);

  useEffect(() => {
    if (tracks.length > 0 && libraryName.trim() === "") {
      setLibraryName(FALLBACK_LIBRARY_NAME);
    }
  }, [libraryName, tracks.length]);

  useEffect(() => {
    const unsubscribe = window.runnerApp.onScanProgress((progress) => {
      setScanProgress(progress);
      if (progress.phase !== "done") {
        setStatus(progress.message);
      }
    });
    return unsubscribe;
  }, []);

  const chooseSourceFolder = useCallback(async () => {
    const nextPath = await window.runnerApp.pickMusicFolder();
    if (nextPath) {
      setSourceFolder(nextPath);
      setScanProgress(null);
      setStatus("已选择音乐目录，可以开始扫描。");
    }
  }, []);

  const chooseExportFolder = useCallback(async () => {
    const nextPath = await window.runnerApp.pickExportFolder();
    if (nextPath) {
      setExportFolder(nextPath);
      setStatus("已选择导出目录。");
    }
  }, []);

  const scanLibrary = useCallback(async () => {
    if (!sourceFolder) {
      setStatus("请先选择音乐目录。");
      return;
    }

    setIsScanning(true);
    setScanProgress({
      phase: "collecting",
      processed: 0,
      total: 0,
      percent: 0,
      message: "准备开始扫描...",
    });
    setStatus("正在扫描 MP3 并提取 BPM、响度和能量特征...");
    try {
      const result = await window.runnerApp.scanFolder(sourceFolder);
      setTracks(result.tracks);
      setLibraryName(result.libraryName || libraryName);
      setStatus(buildScanStatus(result.tracks.length, result.otherAudioExtensions));
    } catch (error) {
      setStatus(toErrorMessage(error, "扫描失败。"));
    } finally {
      setIsScanning(false);
    }
  }, [libraryName, sourceFolder]);

  const exportLibrary = useCallback(async () => {
    if (!exportFolder) {
      setStatus("请先选择导出目录。");
      return;
    }
    if (exportableTracks.length === 0) {
      setStatus("当前没有可导出的歌曲，请先恢复至少一首歌曲。");
      return;
    }

    setStatus("正在导出 RunnerPlayerExport 目录...");
    try {
      const result = await window.runnerApp.exportLibrary({
        libraryName,
        outputDirectory: exportFolder,
        tracks: exportableTracks,
      });
      setStatus(`已导出 ${result.trackCount} 首歌曲到 ${result.outputRoot}。如果不使用 ADB，请把手表切到 USB“传输文件”模式后手动放置文件。`);
      window.alert(
        [
          `导出完成：${result.outputRoot}`,
          "",
          "如果不通过 ADB 推送，请在手表上选择 USB“传输文件”模式。",
          "然后把 RunnerPlayerExport 文件夹手动放到手表的 /sdcard/Music/ 下。",
        ].join("\n"),
      );
    } catch (error) {
      setStatus(toErrorMessage(error, "导出失败。"));
    }
  }, [exportFolder, exportableTracks, libraryName]);

  const updateFinalLabel = useCallback((trackId: string, finalLabel: "calm" | "excited") => {
    setTracks((current) =>
      current.map((track) =>
        track.id === trackId
          ? {
              ...track,
              finalLabel,
            }
          : track,
      ),
    );
  }, []);

  const toggleExcludeFromExport = useCallback((trackId: string) => {
    setTracks((current) =>
      current.map((track) =>
        track.id === trackId
          ? {
              ...track,
              excludedFromExport: !track.excludedFromExport,
            }
          : track,
      ),
    );
  }, []);

  return {
    libraryName,
    sourceFolder,
    exportFolder,
    tracks,
    status,
    isScanning,
    scanProgress,
    exportableTracks,
    stats,
    setLibraryName,
    setStatus,
    chooseSourceFolder,
    chooseExportFolder,
    scanLibrary,
    exportLibrary,
    updateFinalLabel,
    toggleExcludeFromExport,
  };
}
