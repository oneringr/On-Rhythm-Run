import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  AdbDevice,
  AnalyzedTrack,
  PushProgressUpdate,
  RemoteMusicEntry,
  RemoteMusicListing,
  ScanProgressUpdate,
} from "../common/manifest";

const REMOTE_MUSIC_ROOT = "/sdcard/Music";

export default function App() {
  const [libraryName, setLibraryName] = useState("晨跑歌单");
  const [sourceFolder, setSourceFolder] = useState("");
  const [exportFolder, setExportFolder] = useState("");
  const [tracks, setTracks] = useState<AnalyzedTrack[]>([]);
  const [status, setStatus] = useState("请选择一个目录来扫描 MP3 音乐。");
  const [isScanning, setIsScanning] = useState(false);
  const [scanProgress, setScanProgress] = useState<ScanProgressUpdate | null>(null);
  const [showBackToTop, setShowBackToTop] = useState(false);

  const [devices, setDevices] = useState<AdbDevice[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState("");
  const [deviceStatus, setDeviceStatus] = useState("正在等待设备连接。");
  const [isRefreshingDevices, setIsRefreshingDevices] = useState(false);
  const [isPushingToDevice, setIsPushingToDevice] = useState(false);
  const [isPushingRunnerExport, setIsPushingRunnerExport] = useState(false);
  const [isLoadingRemoteMusic, setIsLoadingRemoteMusic] = useState(false);
  const [remoteMusicListing, setRemoteMusicListing] = useState<RemoteMusicListing | null>(null);
  const [pushProgress, setPushProgress] = useState<PushProgressUpdate | null>(null);

  const [previewTitle, setPreviewTitle] = useState("尚未选择试听歌曲");
  const [previewDuration, setPreviewDuration] = useState(0);
  const [previewPosition, setPreviewPosition] = useState(0);
  const [isPreviewPlaying, setIsPreviewPlaying] = useState(false);
  const [isPreviewScrubbing, setIsPreviewScrubbing] = useState(false);
  const [activePreviewTrackId, setActivePreviewTrackId] = useState("");
  const [autoAdvanceOnLabelChange, setAutoAdvanceOnLabelChange] = useState(true);
  const audioRef = useRef<HTMLAudioElement>(null);

  const exportableTracks = useMemo(
    () => tracks.filter((track) => !track.excludedFromExport),
    [tracks],
  );
  const hasPreviewTrack = activePreviewTrackId !== "";
  const activePreviewTrackIndex = useMemo(
    () => tracks.findIndex((track) => track.id === activePreviewTrackId),
    [activePreviewTrackId, tracks],
  );

  const stats = useMemo(() => {
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
      setLibraryName("跑步歌单");
    }
  }, [libraryName, tracks.length]);

  useEffect(() => {
    void refreshAdbDevices();
  }, []);

  useEffect(() => {
    const unsubscribe = window.runnerApp.onScanProgress((progress) => {
      setScanProgress(progress);
      if (progress.phase !== "done") {
        setStatus(progress.message);
      }
    });
    return unsubscribe;
  }, []);

  useEffect(() => {
    const unsubscribe = window.runnerApp.onPushProgress((progress) => {
      setPushProgress(progress);
      setDeviceStatus(
        progress.currentFileName
          ? `${progress.message}：${progress.currentFileName}`
          : progress.message,
      );
    });
    return unsubscribe;
  }, []);

  useEffect(() => {
    const handleScroll = () => {
      setShowBackToTop(window.scrollY > 240);
    };

    handleScroll();
    window.addEventListener("scroll", handleScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", handleScroll);
    };
  }, []);

  useEffect(() => {
    if (!selectedDeviceId) {
      setRemoteMusicListing(null);
      return;
    }
    void loadRemoteMusic(selectedDeviceId, REMOTE_MUSIC_ROOT);
  }, [selectedDeviceId]);

  useEffect(() => {
    const audioElement = audioRef.current;
    if (!audioElement) {
      return;
    }

    const handleLoadedMetadata = () => {
      setPreviewDuration(Number.isFinite(audioElement.duration) ? audioElement.duration : 0);
      setPreviewPosition(audioElement.currentTime || 0);
    };
    const handleTimeUpdate = () => {
      if (!isPreviewScrubbing) {
        setPreviewPosition(audioElement.currentTime || 0);
      }
    };
    const handlePlay = () => setIsPreviewPlaying(true);
    const handlePause = () => setIsPreviewPlaying(false);
    const handleEnded = () => {
      setIsPreviewPlaying(false);
      setIsPreviewScrubbing(false);
      setPreviewPosition(0);
    };

    audioElement.addEventListener("loadedmetadata", handleLoadedMetadata);
    audioElement.addEventListener("timeupdate", handleTimeUpdate);
    audioElement.addEventListener("play", handlePlay);
    audioElement.addEventListener("pause", handlePause);
    audioElement.addEventListener("ended", handleEnded);

    return () => {
      audioElement.removeEventListener("loadedmetadata", handleLoadedMetadata);
      audioElement.removeEventListener("timeupdate", handleTimeUpdate);
      audioElement.removeEventListener("play", handlePlay);
      audioElement.removeEventListener("pause", handlePause);
      audioElement.removeEventListener("ended", handleEnded);
    };
  }, [isPreviewScrubbing]);

  async function chooseSourceFolder() {
    const nextPath = await window.runnerApp.pickMusicFolder();
    if (nextPath) {
      setSourceFolder(nextPath);
      setScanProgress(null);
      setStatus("已选择音乐目录，可以开始扫描。");
    }
  }

  async function chooseExportFolder() {
    const nextPath = await window.runnerApp.pickExportFolder();
    if (nextPath) {
      setExportFolder(nextPath);
      setStatus("已选择导出目录。");
    }
  }

  async function scanLibrary() {
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
  }

  async function exportLibrary() {
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
      setStatus(`已导出 ${result.trackCount} 首歌曲到 ${result.outputRoot}`);
    } catch (error) {
      setStatus(toErrorMessage(error, "导出失败。"));
    }
  }

  async function refreshAdbDevices() {
    setIsRefreshingDevices(true);
    try {
      const deviceList = await window.runnerApp.listAdbDevices();
      setDevices(deviceList);

      if (deviceList.length === 0) {
        setSelectedDeviceId("");
        setDeviceStatus("未检测到 ADB 设备，请确认手表已连接并开启调试。");
        return;
      }

      const nextSelectedDeviceId =
        deviceList.find((device) => device.id === selectedDeviceId)?.id ?? deviceList[0]?.id ?? "";
      setSelectedDeviceId(nextSelectedDeviceId);
      setDeviceStatus(`已检测到 ${deviceList.length} 台设备，可推送到 /sdcard/Music。`);
    } catch (error) {
      setDeviceStatus(toErrorMessage(error, "读取设备列表失败。"));
    } finally {
      setIsRefreshingDevices(false);
    }
  }

  async function pushTracksToDevice() {
    if (!selectedDeviceId) {
      setDeviceStatus("请先选择一台设备。");
      return;
    }
    if (exportableTracks.length === 0) {
      setDeviceStatus("当前没有可推送的歌曲，请先恢复至少一首歌曲。");
      return;
    }

    setIsPushingToDevice(true);
    setPushProgress({
      target: "tracks",
      phase: "preparing",
      processed: 0,
      total: exportableTracks.length,
      percent: 0,
      message: "正在准备歌曲推送...",
    });
    setDeviceStatus("正在通过 ADB 推送歌曲到手表...");
    try {
      const result = await window.runnerApp.pushTracksToDevice({
        deviceId: selectedDeviceId,
        libraryName,
        tracks: exportableTracks,
      });
      setDeviceStatus(`已推送 ${result.pushedCount} 首歌曲到 ${result.remotePath}`);
      await loadRemoteMusic(selectedDeviceId, result.remotePath);
    } catch (error) {
      setDeviceStatus(toErrorMessage(error, "ADB 推送失败。"));
    } finally {
      setIsPushingToDevice(false);
    }
  }

  async function pushRunnerExportToDevice() {
    if (!selectedDeviceId) {
      setDeviceStatus("请先选择一台设备。");
      return;
    }
    if (exportableTracks.length === 0) {
      setDeviceStatus("当前没有可推送的歌曲和标签，请先恢复至少一首歌曲。");
      return;
    }

    setIsPushingRunnerExport(true);
    setPushProgress({
      target: "runner-export",
      phase: "preparing",
      processed: 0,
      total: exportableTracks.length + 1,
      percent: 0,
      message: "正在准备完整曲库推送...",
    });
    setDeviceStatus("正在通过 ADB 推送完整曲库到 /sdcard/Music/RunnerPlayerExport ...");
    try {
      const result = await window.runnerApp.pushRunnerExportToDevice({
        deviceId: selectedDeviceId,
        libraryName,
        tracks: exportableTracks,
      });
      setDeviceStatus(
        `已推送 ${result.trackCount} 首歌曲和 manifest 到 ${result.remotePath}`,
      );
    } catch (error) {
      setDeviceStatus(toErrorMessage(error, "ADB 推送完整曲库失败。"));
    } finally {
      setIsPushingRunnerExport(false);
    }
  }

  async function loadRemoteMusic(deviceId: string, remotePath: string) {
    setIsLoadingRemoteMusic(true);
    try {
      const listing = await window.runnerApp.listRemoteMusic({ deviceId, remotePath });
      setRemoteMusicListing(listing);
    } catch (error) {
      setDeviceStatus(toErrorMessage(error, "读取设备文件失败。"));
    } finally {
      setIsLoadingRemoteMusic(false);
    }
  }

  async function deleteRemoteMusicEntry(entry: RemoteMusicEntry) {
    if (!selectedDeviceId) {
      setDeviceStatus("请先选择一台设备。");
      return;
    }
    const confirmed = window.confirm(
      `确认删除设备上的${entry.isDirectory ? "文件夹" : "文件"}“${entry.name}”吗？`,
    );
    if (!confirmed) {
      return;
    }

    setDeviceStatus(`正在删除 ${entry.name}...`);
    try {
      await window.runnerApp.deleteRemoteEntry({
        deviceId: selectedDeviceId,
        remotePath: entry.path,
      });
      setDeviceStatus(`已删除 ${entry.name}`);
      await loadRemoteMusic(selectedDeviceId, remoteMusicListing?.currentPath ?? REMOTE_MUSIC_ROOT);
    } catch (error) {
      setDeviceStatus(toErrorMessage(error, "删除设备文件失败。"));
    }
  }

  const previewTrack = useCallback(async (track: AnalyzedTrack) => {
    try {
      const url = await window.runnerApp.toPreviewUrl(track.sourcePath);
      const title = `${track.title} - ${track.artist}`;
      setPreviewTitle(title);
      setActivePreviewTrackId(track.id);

      const audioElement = audioRef.current;
      if (!audioElement) {
        return;
      }

      audioElement.pause();
      if (audioElement.src !== url) {
        audioElement.src = url;
      }
      setPreviewDuration(0);
      setIsPreviewScrubbing(false);
      audioElement.currentTime = 0;
      setPreviewPosition(0);

      try {
        await audioElement.play();
        setStatus(`正在试听：${title}`);
      } catch (error) {
        setStatus(
          error instanceof Error
            ? `试听播放失败：${error.message}`
            : "已加载试听歌曲，请点击下方播放器继续播放。",
        );
      }
    } catch (error) {
      setStatus(toErrorMessage(error, "试听加载失败。"));
    }
  }, []);

  const togglePreviewPlayback = useCallback(async () => {
    const audioElement = audioRef.current;
    if (!audioElement || !audioElement.src) {
      setStatus("请先选择一首歌曲试听。");
      return;
    }

    try {
      if (audioElement.paused) {
        await audioElement.play();
      } else {
        audioElement.pause();
      }
    } catch (error) {
      setStatus(toErrorMessage(error, "试听播放失败。"));
    }
  }, []);

  const seekPreview = useCallback((nextPosition: number) => {
    const audioElement = audioRef.current;
    setPreviewPosition(nextPosition);
    if (audioElement) {
      audioElement.currentTime = nextPosition;
    }
  }, []);

  const previewRelativeTrack = useCallback((delta: number) => {
    if (tracks.length === 0) {
      setStatus("请先扫描并选择一首歌曲试听。");
      return;
    }

    let nextIndex = 0;
    if (activePreviewTrackIndex >= 0) {
      nextIndex = (activePreviewTrackIndex + delta + tracks.length) % tracks.length;
    } else if (delta < 0) {
      nextIndex = tracks.length - 1;
    }

    void previewTrack(tracks[nextIndex]);
  }, [activePreviewTrackIndex, previewTrack, tracks]);

  const seekPreviewByDelta = useCallback((deltaSeconds: number) => {
    const audioElement = audioRef.current;
    if (!audioElement || !audioElement.src) {
      setStatus("请先选择一首歌曲试听。");
      return;
    }

    const duration = Number.isFinite(audioElement.duration) ? audioElement.duration : previewDuration;
    const maxDuration = Math.max(duration || 0, 0);
    const nextPosition = Math.min(
      Math.max((audioElement.currentTime || 0) + deltaSeconds, 0),
      maxDuration,
    );
    seekPreview(nextPosition);
  }, [previewDuration, seekPreview]);

  function startPreviewScrub() {
    setIsPreviewScrubbing(true);
  }

  function finishPreviewScrub() {
    setIsPreviewScrubbing(false);
  }

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

  const applyLabelShortcut = useCallback((finalLabel: "calm" | "excited") => {
    if (!hasPreviewTrack || activePreviewTrackIndex < 0) {
      setStatus("请先选择一首歌曲试听。");
      return;
    }

    const track = tracks[activePreviewTrackIndex];
    if (!track) {
      return;
    }

    updateFinalLabel(track.id, finalLabel);
    setStatus(
      autoAdvanceOnLabelChange
        ? `已将 ${track.title} 标记为${toChineseLabel(finalLabel)}，并切换到下一首。`
        : `已将 ${track.title} 标记为${toChineseLabel(finalLabel)}。`,
    );

    if (autoAdvanceOnLabelChange && tracks.length > 1) {
      previewRelativeTrack(1);
    }
  }, [
    activePreviewTrackIndex,
    autoAdvanceOnLabelChange,
    hasPreviewTrack,
    previewRelativeTrack,
    tracks,
    updateFinalLabel,
  ]);

  const locateActivePreviewTrack = useCallback(() => {
    if (!hasPreviewTrack) {
      setStatus("请先选择一首歌曲试听。");
      return;
    }

    const rowElement = document.getElementById(`track-row-${activePreviewTrackId}`);
    if (!rowElement) {
      setStatus("未找到当前试听歌曲在列表中的位置。");
      return;
    }

    rowElement.scrollIntoView({
      behavior: "smooth",
      block: "center",
    });
  }, [activePreviewTrackId, hasPreviewTrack]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
        return;
      }

      const target = event.target;
      if (
        target instanceof HTMLElement &&
        (target.isContentEditable || target.matches("input, textarea, select"))
      ) {
        return;
      }

      switch (event.key) {
        case " ":
        case "Spacebar":
          event.preventDefault();
          void togglePreviewPlayback();
          break;
        case "ArrowLeft":
          if (!hasPreviewTrack) {
            return;
          }
          event.preventDefault();
          seekPreviewByDelta(-5);
          break;
        case "ArrowRight":
          if (!hasPreviewTrack) {
            return;
          }
          event.preventDefault();
          seekPreviewByDelta(5);
          break;
        case "ArrowUp":
          event.preventDefault();
          previewRelativeTrack(-1);
          break;
        case "ArrowDown":
          event.preventDefault();
          previewRelativeTrack(1);
          break;
        case "1":
          event.preventDefault();
          applyLabelShortcut("calm");
          break;
        case "2":
          event.preventDefault();
          applyLabelShortcut("excited");
          break;
        case "0":
          event.preventDefault();
          setAutoAdvanceOnLabelChange((current) => {
            const nextValue = !current;
            setStatus(`更改标签后自动下一曲已${nextValue ? "开启" : "关闭"}。`);
            return nextValue;
          });
          break;
        default:
          break;
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [
    applyLabelShortcut,
    hasPreviewTrack,
    previewRelativeTrack,
    seekPreviewByDelta,
    togglePreviewPlayback,
  ]);

  function scrollToTop() {
    window.scrollTo({
      top: 0,
      behavior: "smooth",
    });
  }

  return (
    <div className="app-shell">
      <div className="backdrop-glow backdrop-glow-left" />
      <div className="backdrop-glow backdrop-glow-right" />
      <aside className="control-panel">
        <div>
          <p className="eyebrow">跑者智能音乐播放器工作台</p>
          <h1>为手表自适应播放整理你的跑步歌单。</h1>
          <p className="intro-copy">
            扫描 MP3、估算 BPM 与能量、人工修正标签，然后导出手表可直接读取的
            <code> RunnerPlayerExport </code>目录。
          </p>
        </div>

        <section className="panel-card">
          <label className="field-label" htmlFor="libraryName">
            曲库名称
          </label>
          <input
            id="libraryName"
            className="text-field"
            value={libraryName}
            onChange={(event) => setLibraryName(event.target.value)}
          />

          <div className="button-row">
            <button className="primary-button" onClick={chooseSourceFolder}>
              选择音乐目录
            </button>
            <button
              className="secondary-button"
              onClick={scanLibrary}
              disabled={!sourceFolder || isScanning}
            >
              {isScanning ? "扫描中..." : "扫描 MP3"}
            </button>
          </div>

          <div className="path-block">
            <span className="field-label">源目录</span>
            <span>{sourceFolder || "尚未选择音乐目录"}</span>
            <span className="support-hint">
              当前仅扫描当前文件夹中的 .mp3 文件，.m4a / .flac 等格式不会导入。
            </span>
          </div>

          <div className={`scan-feedback ${status.includes("没有找到") || status.includes("仅发现") ? "warning" : ""}`}>
            {status}
          </div>

          {scanProgress ? (
            <div className={`scan-progress ${isScanning ? "active" : ""}`}>
              <div className="scan-progress-header">
                <span>{scanProgress.message}</span>
                <strong>{Math.min(scanProgress.percent, 100)}%</strong>
              </div>
              <div className="scan-progress-track" aria-hidden="true">
                <div
                  className="scan-progress-fill"
                  style={{ width: `${Math.min(scanProgress.percent, 100)}%` }}
                />
              </div>
              <div className="support-hint">
                {scanProgress.total > 0
                  ? `${scanProgress.processed}/${scanProgress.total} 首`
                  : "正在准备扫描任务"}
              </div>
            </div>
          ) : null}
        </section>

        <section className="panel-card">
          <div className="summary-grid">
            <SummaryCard label="总数" value={stats.total.toString()} accent="mint" />
            <SummaryCard label="待导出" value={stats.exportable.toString()} accent="mint" />
            <SummaryCard label="已排除" value={stats.excluded.toString()} accent="salmon" />
            <SummaryCard label="舒缓" value={stats.calm.toString()} accent="mint" />
            <SummaryCard label="激动" value={stats.excited.toString()} accent="salmon" />
          </div>
        </section>

        <section className="panel-card">
          <div className="button-row">
            <button className="primary-button" onClick={chooseExportFolder}>
              选择导出目录
            </button>
            <button className="secondary-button" onClick={exportLibrary}>
              导出曲库
            </button>
          </div>

          <div className="path-block">
            <span className="field-label">导出目录</span>
            <span>{exportFolder || "尚未选择导出目录"}</span>
          </div>
        </section>

        <section className="panel-card">
          <div className="panel-header">
            <div>
              <span className="field-label">ADB 同步</span>
              <h2>推送到 /sdcard/Music</h2>
            </div>
            <button
              className="ghost-button"
              onClick={refreshAdbDevices}
              disabled={isRefreshingDevices}
            >
              {isRefreshingDevices ? "刷新中..." : "刷新设备"}
            </button>
          </div>

          <label className="field-label" htmlFor="adbDeviceSelect">
            已连接设备
          </label>
          <select
            id="adbDeviceSelect"
            className="label-select"
            value={selectedDeviceId}
            onChange={(event) => setSelectedDeviceId(event.target.value)}
          >
            {devices.length === 0 ? (
              <option value="">未检测到设备</option>
            ) : (
              devices.map((device) => (
                <option key={device.id} value={device.id}>
                  {formatDevice(device)}
                </option>
              ))
            )}
          </select>

          <div className="button-row">
            <button
              className="primary-button"
              onClick={pushTracksToDevice}
              disabled={
                !selectedDeviceId ||
                isPushingToDevice ||
                isPushingRunnerExport ||
                exportableTracks.length === 0
              }
            >
              {isPushingToDevice ? "推送中..." : "一键推送歌曲"}
            </button>
            <button
              className="primary-button"
              onClick={pushRunnerExportToDevice}
              disabled={
                !selectedDeviceId ||
                isPushingToDevice ||
                isPushingRunnerExport ||
                exportableTracks.length === 0
              }
            >
              {isPushingRunnerExport ? "推送中..." : "推送完整曲库"}
            </button>
            <button
              className="secondary-button"
              onClick={() =>
                selectedDeviceId &&
                loadRemoteMusic(selectedDeviceId, remoteMusicListing?.currentPath ?? REMOTE_MUSIC_ROOT)
              }
              disabled={!selectedDeviceId || isLoadingRemoteMusic}
            >
              {isLoadingRemoteMusic ? "读取中..." : "刷新文件"}
            </button>
          </div>

          <div className="path-block">
            <span className="field-label">设备状态</span>
            <span>{deviceStatus}</span>
            <span className="support-hint">
              “一键推送歌曲”会发送到 <code>/sdcard/Music/曲库名</code>，
              “推送完整曲库”会发送到 <code>/sdcard/Music/RunnerPlayerExport</code> 并附带 <code>runner_manifest.json</code>。
            </span>
          </div>

          {pushProgress ? (
            <div className={`scan-progress ${(isPushingToDevice || isPushingRunnerExport) ? "active" : ""}`}>
              <div className="scan-progress-header">
                <span>
                  {pushProgress.target === "runner-export" ? "完整曲库推送" : "歌曲推送"}
                </span>
                <strong>{Math.min(pushProgress.percent, 100)}%</strong>
              </div>
              <div className="scan-progress-track" aria-hidden="true">
                <div
                  className="scan-progress-fill"
                  style={{ width: `${Math.min(pushProgress.percent, 100)}%` }}
                />
              </div>
              <div className="support-hint">
                {pushProgress.currentFileName
                  ? `${pushProgress.processed}/${pushProgress.total} · ${pushProgress.currentFileName}`
                  : `${pushProgress.processed}/${pushProgress.total}`}
              </div>
            </div>
          ) : null}

          <div className="summary-grid remote-stats-grid">
            <SummaryCard
              label="文件夹"
              value={String(remoteMusicListing?.stats.directoryCount ?? 0)}
              accent="mint"
            />
            <SummaryCard
              label="当前文件"
              value={String(remoteMusicListing?.stats.fileCount ?? 0)}
              accent="mint"
            />
            <SummaryCard
              label="当前 MP3"
              value={String(remoteMusicListing?.stats.mp3Count ?? 0)}
              accent="salmon"
            />
            <SummaryCard
              label="当前树 MP3"
              value={String(remoteMusicListing?.stats.recursiveMp3Count ?? 0)}
              accent="salmon"
            />
          </div>

          <div className="remote-browser">
            <div className="remote-browser-toolbar">
              <div className="remote-browser-toolbar-copy">
                <span className="field-label">当前目录</span>
                <div
                  className="remote-path"
                  title={remoteMusicListing?.currentPath ?? REMOTE_MUSIC_ROOT}
                >
                  {remoteMusicListing?.currentPath ?? REMOTE_MUSIC_ROOT}
                </div>
              </div>
              <div className="mini-button-row">
                <button
                  className="ghost-button"
                  onClick={() =>
                    selectedDeviceId &&
                    remoteMusicListing?.parentPath &&
                    loadRemoteMusic(selectedDeviceId, remoteMusicListing.parentPath)
                  }
                  disabled={!selectedDeviceId || !remoteMusicListing?.parentPath || isLoadingRemoteMusic}
                >
                  上一级
                </button>
                <button
                  className="ghost-button"
                  onClick={() =>
                    selectedDeviceId &&
                    loadRemoteMusic(selectedDeviceId, remoteMusicListing?.currentPath ?? REMOTE_MUSIC_ROOT)
                  }
                  disabled={!selectedDeviceId || isLoadingRemoteMusic}
                >
                  刷新
                </button>
              </div>
            </div>

            <div className="remote-entry-list">
              {remoteMusicListing?.entries.length ? (
                remoteMusicListing.entries.map((entry) => (
                  <div className="remote-entry" key={entry.path}>
                    <div className="remote-entry-text">
                      <strong>{entry.name}</strong>
                      <span>{entry.isDirectory ? "文件夹" : "文件"}</span>
                    </div>
                    <div className="remote-entry-actions">
                      {entry.isDirectory ? (
                        <button
                          className="ghost-button"
                          onClick={() => selectedDeviceId && loadRemoteMusic(selectedDeviceId, entry.path)}
                        >
                          打开
                        </button>
                      ) : null}
                      <button className="danger-button" onClick={() => void deleteRemoteMusicEntry(entry)}>
                        删除
                      </button>
                    </div>
                  </div>
                ))
              ) : (
                <div className="empty-state compact">
                  {selectedDeviceId ? "当前目录没有文件。" : "连接设备后可浏览 /sdcard/Music。"}
                </div>
              )}
            </div>
          </div>
        </section>
      </aside>

      <main className="table-panel">
        <div className="table-header">
          <div>
            <p className="eyebrow">标签校对</p>
            <h2>确认每首歌的舒缓 / 激动标签</h2>
          </div>
        </div>

        <div className="track-table">
          <div className="track-row track-row-head">
            <span>歌曲</span>
            <span>BPM</span>
            <span>RMS</span>
            <span>频谱中心</span>
            <span>建议标签</span>
            <span>最终标签</span>
            <span>试听</span>
            <span>导出</span>
          </div>

          {tracks.map((track) => (
            <TrackRow
              key={track.id}
              track={track}
              isActive={track.id === activePreviewTrackId}
              onPreview={previewTrack}
              onLabelChange={updateFinalLabel}
              onToggleExclude={toggleExcludeFromExport}
            />
          ))}

          {tracks.length === 0 ? (
            <div className="empty-state">
              还没有分析结果。请选择目录后开始扫描。
            </div>
          ) : null}
        </div>
      </main>

      <section className={`floating-preview-bar ${hasPreviewTrack ? "active" : ""}`}>
        <audio
          ref={audioRef}
          preload="metadata"
          className="hidden-audio"
          onError={() => setStatus("试听失败：当前文件无法在预览播放器中打开。")}
        >
          当前环境不支持音频试听。
        </audio>

        <div className="floating-preview-header">
          <div className="floating-preview-copy">
            <span className="field-label floating-preview-label">试听</span>
            <strong className="floating-preview-title">{previewTitle}</strong>
            <span className="support-hint floating-preview-hint">
              {activePreviewTrackId
                ? `空格播放/暂停，左右键快退/快进 5 秒，上下键切歌，1/2 改标签，0 自动下一曲：${autoAdvanceOnLabelChange ? "开" : "关"}`
                : "请在表格中选择一首歌开始试听"}
            </span>
          </div>
          <div className="floating-preview-actions">
            <button
              className="ghost-button floating-preview-locate-button"
              onClick={locateActivePreviewTrack}
              disabled={!hasPreviewTrack}
            >
              定位当前
            </button>
            <button
              className="primary-button floating-preview-button"
              onClick={() => void togglePreviewPlayback()}
              disabled={!hasPreviewTrack}
            >
              {isPreviewPlaying ? "暂停" : "播放"}
            </button>
          </div>
        </div>

        <div className="preview-timeline floating-preview-timeline">
          <span>{formatDuration(previewPosition)}</span>
          <input
            type="range"
            min={0}
            max={Math.max(previewDuration, 0.1)}
            step={0.1}
            value={Math.min(previewPosition, previewDuration || 0)}
            onPointerDown={startPreviewScrub}
            onPointerUp={finishPreviewScrub}
            onBlur={finishPreviewScrub}
            onInput={(event) => seekPreview(Number(event.currentTarget.value))}
            onChange={(event) => seekPreview(Number(event.currentTarget.value))}
            disabled={!hasPreviewTrack}
          />
          <span>{formatDuration(previewDuration)}</span>
        </div>
      </section>

      <button
        type="button"
        className={`back-to-top-button ${showBackToTop ? "visible" : ""}`}
        onClick={scrollToTop}
        aria-label="回到顶部"
      >
        ∧
      </button>
    </div>
  );
}

type TrackRowProps = {
  track: AnalyzedTrack;
  isActive: boolean;
  onPreview: (track: AnalyzedTrack) => Promise<void>;
  onLabelChange: (trackId: string, finalLabel: "calm" | "excited") => void;
  onToggleExclude: (trackId: string) => void;
};

const TrackRow = memo(function TrackRow({
  track,
  isActive,
  onPreview,
  onLabelChange,
  onToggleExclude,
}: TrackRowProps) {
  return (
    <div
      id={`track-row-${track.id}`}
      className={`track-row ${track.excludedFromExport ? "excluded" : ""} ${isActive ? "active-preview" : ""}`}
    >
      <div>
        <strong>{track.title}</strong>
        <span>{track.artist}</span>
        {track.excludedFromExport ? <span className="track-flag">已排除导出</span> : null}
      </div>
      <span>{Math.round(track.bpm)}</span>
      <span>{track.rms.toFixed(3)}</span>
      <span>{Math.round(track.spectralCentroid)}</span>
      <span className={`label-pill ${track.suggestedLabel}`}>{toChineseLabel(track.suggestedLabel)}</span>
      <select
        className="label-select"
        value={track.finalLabel}
        onChange={(event) => onLabelChange(track.id, event.target.value as "calm" | "excited")}
        disabled={track.excludedFromExport}
      >
        <option value="calm">舒缓</option>
        <option value="excited">激动</option>
      </select>
      <button className="preview-button" onClick={() => void onPreview(track)}>
        试听
      </button>
      <button
        className={track.excludedFromExport ? "ghost-button" : "danger-button"}
        onClick={() => onToggleExclude(track.id)}
      >
        {track.excludedFromExport ? "恢复" : "删除"}
      </button>
    </div>
  );
});

function buildScanStatus(trackCount: number, otherAudioExtensions: string[]): string {
  if (trackCount > 0) {
    return `已扫描 ${trackCount} 首歌曲，请确认标签后再导出。`;
  }

  if (otherAudioExtensions.length > 0) {
    return `当前目录中没有找到 .mp3 文件，仅发现 ${formatExtensions(otherAudioExtensions)}。PC 端当前只扫描当前文件夹中的 MP3。`;
  }

  return "当前目录中没有找到 .mp3 文件，请重新选择包含 MP3 的目录。";
}

function formatExtensions(extensions: string[]): string {
  return extensions.map((extension) => extension.toLowerCase()).join("、");
}

function formatDevice(device: AdbDevice): string {
  const parts = [device.model, device.deviceName, device.id].filter(Boolean);
  return `${parts.join(" / ")}${device.state && device.state !== "device" ? ` (${device.state})` : ""}`;
}

function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return "00:00";
  }
  const totalSeconds = Math.floor(seconds);
  const minutes = Math.floor(totalSeconds / 60);
  const remainingSeconds = totalSeconds % 60;
  return `${minutes.toString().padStart(2, "0")}:${remainingSeconds.toString().padStart(2, "0")}`;
}

function toErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function SummaryCard(props: { label: string; value: string; accent: "mint" | "salmon" }) {
  return (
    <div className={`summary-card ${props.accent}`}>
      <span>{props.label}</span>
      <strong>{props.value}</strong>
    </div>
  );
}

function toChineseLabel(label: "calm" | "excited"): string {
  return label === "calm" ? "舒缓" : "激动";
}
