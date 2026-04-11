import { useCallback, useEffect, useRef, useState } from "react";
import { AdbSyncPanel } from "./components/AdbSyncPanel";
import { AudioPreviewBar } from "./components/AudioPreviewBar";
import { LibraryScanPanel } from "./components/LibraryScanPanel";
import { StatsBar } from "./components/StatsBar";
import { TrackTable } from "./components/TrackTable";
import { useAdbSync } from "./hooks/useAdbSync";
import { useAudioPreview } from "./hooks/useAudioPreview";
import { useKeyboardShortcuts } from "./hooks/useKeyboardShortcuts";
import { useLibraryScan } from "./hooks/useLibraryScan";
import { toChineseLabel } from "./utils/formatting";

export default function App() {
  const audioRef = useRef<HTMLAudioElement>(null);
  const [showBackToTop, setShowBackToTop] = useState(false);

  const library = useLibraryScan();
  const preview = useAudioPreview({
    audioRef,
    tracks: library.tracks,
    onStatusChange: library.setStatus,
  });
  const adb = useAdbSync({
    libraryName: library.libraryName,
    exportableTracks: library.exportableTracks,
  });

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

  const applyPreviewLabelShortcut = useCallback((finalLabel: "calm" | "excited") => {
    if (!preview.activePreviewTrack) {
      library.setStatus("请先选择一首歌曲试听。");
      return;
    }

    library.updateFinalLabel(preview.activePreviewTrack.id, finalLabel);
    library.setStatus(
      preview.autoAdvanceOnLabelChange
        ? `已将 ${preview.activePreviewTrack.title} 标记为${toChineseLabel(finalLabel)}，并切换到下一首。`
        : `已将 ${preview.activePreviewTrack.title} 标记为${toChineseLabel(finalLabel)}。`,
    );

    if (preview.autoAdvanceOnLabelChange && library.tracks.length > 1) {
      preview.previewRelativeTrack(1);
    }
  }, [
    library,
    preview.activePreviewTrack,
    preview.autoAdvanceOnLabelChange,
    preview.previewRelativeTrack,
  ]);

  const toggleAutoAdvanceOnLabelChange = useCallback(() => {
    preview.toggleAutoAdvanceOnLabelChange((enabled) => {
      library.setStatus(`更改标签后自动下一曲已${enabled ? "开启" : "关闭"}。`);
    });
  }, [library, preview]);

  useKeyboardShortcuts({
    hasPreviewTrack: preview.hasPreviewTrack,
    onTogglePreviewPlayback: preview.togglePreviewPlayback,
    onSeekPreviewByDelta: preview.seekPreviewByDelta,
    onPreviewRelativeTrack: preview.previewRelativeTrack,
    onApplyCalmLabel: () => applyPreviewLabelShortcut("calm"),
    onApplyExcitedLabel: () => applyPreviewLabelShortcut("excited"),
    onToggleAutoAdvance: toggleAutoAdvanceOnLabelChange,
  });

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

        <LibraryScanPanel
          libraryName={library.libraryName}
          sourceFolder={library.sourceFolder}
          exportFolder={library.exportFolder}
          status={library.status}
          isScanning={library.isScanning}
          scanProgress={library.scanProgress}
          onLibraryNameChange={library.setLibraryName}
          onChooseSourceFolder={library.chooseSourceFolder}
          onChooseExportFolder={library.chooseExportFolder}
          onScanLibrary={library.scanLibrary}
          onExportLibrary={library.exportLibrary}
        />

        <StatsBar stats={library.stats} />

        <AdbSyncPanel
          devices={adb.devices}
          selectedDeviceId={adb.selectedDeviceId}
          deviceStatus={adb.deviceStatus}
          isRefreshingDevices={adb.isRefreshingDevices}
          isPushingToDevice={adb.isPushingToDevice}
          isPushingRunnerExport={adb.isPushingRunnerExport}
          isLoadingRemoteMusic={adb.isLoadingRemoteMusic}
          remoteMusicListing={adb.remoteMusicListing}
          pushProgress={adb.pushProgress}
          exportableTrackCount={library.exportableTracks.length}
          onSelectedDeviceChange={adb.setSelectedDeviceId}
          onRefreshDevices={adb.refreshAdbDevices}
          onPushTracksToDevice={adb.pushTracksToDevice}
          onPushRunnerExportToDevice={adb.pushRunnerExportToDevice}
          onLoadRemoteMusic={adb.loadRemoteMusic}
          onDeleteRemoteEntry={adb.deleteRemoteMusicEntry}
        />
      </aside>

      <TrackTable
        tracks={library.tracks}
        activePreviewTrackId={preview.activePreviewTrackId}
        onPreview={preview.previewTrack}
        onLabelChange={library.updateFinalLabel}
        onToggleExclude={library.toggleExcludeFromExport}
      />

      <AudioPreviewBar
        audioRef={audioRef}
        previewTitle={preview.previewTitle}
        previewDuration={preview.previewDuration}
        previewPosition={preview.previewPosition}
        isPreviewPlaying={preview.isPreviewPlaying}
        hasPreviewTrack={preview.hasPreviewTrack}
        activePreviewTrackId={preview.activePreviewTrackId}
        autoAdvanceOnLabelChange={preview.autoAdvanceOnLabelChange}
        onLocateActivePreviewTrack={preview.locateActivePreviewTrack}
        onTogglePreviewPlayback={preview.togglePreviewPlayback}
        onSeekPreview={preview.seekPreview}
        onStartPreviewScrub={preview.startPreviewScrub}
        onFinishPreviewScrub={preview.finishPreviewScrub}
        onPreviewError={() => library.setStatus("试听失败：当前文件无法在预览播放器中打开。")}
      />

      <button
        type="button"
        className={`back-to-top-button ${showBackToTop ? "visible" : ""}`}
        onClick={() =>
          window.scrollTo({
            top: 0,
            behavior: "smooth",
          })
        }
        aria-label="回到顶部"
      >
        ∧
      </button>
    </div>
  );
}
