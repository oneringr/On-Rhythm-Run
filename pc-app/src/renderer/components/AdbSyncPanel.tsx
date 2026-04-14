import type { AdbDevice, PushProgressUpdate, RemoteMusicListing } from "../../common/manifest";
import { formatDevice } from "../utils/formatting";
import { RemoteBrowser } from "./RemoteBrowser";
import { SummaryCard } from "./SummaryCard";

type AdbSyncPanelProps = {
  devices: AdbDevice[];
  selectedDeviceId: string;
  deviceStatus: string;
  isRefreshingDevices: boolean;
  isPushingToDevice: boolean;
  isPushingRunnerExport: boolean;
  isLoadingRemoteMusic: boolean;
  remoteMusicListing: RemoteMusicListing | null;
  pushProgress: PushProgressUpdate | null;
  exportableTrackCount: number;
  onSelectedDeviceChange: (deviceId: string) => void;
  onRefreshDevices: () => void | Promise<void>;
  onPushTracksToDevice: () => void | Promise<void>;
  onPushRunnerExportToDevice: () => void | Promise<void>;
  onLoadRemoteMusic: (deviceId: string, remotePath: string) => void | Promise<void>;
  onDeleteRemoteEntry: Parameters<typeof RemoteBrowser>[0]["onDeleteRemoteEntry"];
};

export function AdbSyncPanel({
  devices,
  selectedDeviceId,
  deviceStatus,
  isRefreshingDevices,
  isPushingToDevice,
  isPushingRunnerExport,
  isLoadingRemoteMusic,
  remoteMusicListing,
  pushProgress,
  exportableTrackCount,
  onSelectedDeviceChange,
  onRefreshDevices,
  onPushTracksToDevice,
  onPushRunnerExportToDevice,
  onLoadRemoteMusic,
  onDeleteRemoteEntry,
}: AdbSyncPanelProps) {
  return (
    <section className="panel-card">
      <div className="panel-header">
        <div>
          <span className="field-label">内置 ADB 同步</span>
          <h2>推送到 /sdcard/Music</h2>
        </div>
        <button
          className="ghost-button"
          onClick={onRefreshDevices}
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
        onChange={(event) => onSelectedDeviceChange(event.target.value)}
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
          onClick={onPushTracksToDevice}
          disabled={
            !selectedDeviceId ||
            isPushingToDevice ||
            isPushingRunnerExport ||
            exportableTrackCount === 0
          }
        >
          {isPushingToDevice ? "推送中..." : "一键推送歌曲"}
        </button>
        <button
          className="primary-button"
          onClick={onPushRunnerExportToDevice}
          disabled={
            !selectedDeviceId ||
            isPushingToDevice ||
            isPushingRunnerExport ||
            exportableTrackCount === 0
          }
        >
          {isPushingRunnerExport ? "推送中..." : "推送完整曲库"}
        </button>
        <button
          className="secondary-button"
          onClick={() =>
            selectedDeviceId &&
            onLoadRemoteMusic(selectedDeviceId, remoteMusicListing?.currentPath ?? "/sdcard/Music")
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
          当前 Windows 包会优先使用内置 ADB；如果不想开调试，也可以先导出，再把手表切到 USB“传输文件”模式后手动放置文件。
        </span>
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

      <RemoteBrowser
        selectedDeviceId={selectedDeviceId}
        isLoadingRemoteMusic={isLoadingRemoteMusic}
        remoteMusicListing={remoteMusicListing}
        onLoadRemoteMusic={onLoadRemoteMusic}
        onDeleteRemoteEntry={onDeleteRemoteEntry}
      />
    </section>
  );
}
