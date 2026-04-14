import { useCallback, useEffect, useState } from "react";
import type {
  PushProgressUpdate,
  RemoteMusicEntry,
  RemoteMusicListing,
  AnalyzedTrack,
  AdbDevice,
} from "../../common/manifest";
import { INITIAL_DEVICE_STATUS, REMOTE_MUSIC_ROOT } from "../config";
import { toErrorMessage } from "../utils/formatting";

type UseAdbSyncOptions = {
  libraryName: string;
  exportableTracks: AnalyzedTrack[];
};

export function useAdbSync({ libraryName, exportableTracks }: UseAdbSyncOptions) {
  const [devices, setDevices] = useState<AdbDevice[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState("");
  const [deviceStatus, setDeviceStatus] = useState(INITIAL_DEVICE_STATUS);
  const [isRefreshingDevices, setIsRefreshingDevices] = useState(false);
  const [isPushingToDevice, setIsPushingToDevice] = useState(false);
  const [isPushingRunnerExport, setIsPushingRunnerExport] = useState(false);
  const [isLoadingRemoteMusic, setIsLoadingRemoteMusic] = useState(false);
  const [remoteMusicListing, setRemoteMusicListing] = useState<RemoteMusicListing | null>(null);
  const [pushProgress, setPushProgress] = useState<PushProgressUpdate | null>(null);

  const loadRemoteMusic = useCallback(async (deviceId: string, remotePath: string) => {
    setIsLoadingRemoteMusic(true);
    try {
      const listing = await window.runnerApp.listRemoteMusic({ deviceId, remotePath });
      setRemoteMusicListing(listing);
    } catch (error) {
      setDeviceStatus(toErrorMessage(error, "读取设备文件失败。"));
    } finally {
      setIsLoadingRemoteMusic(false);
    }
  }, []);

  const refreshAdbDevices = useCallback(async () => {
    setIsRefreshingDevices(true);
    try {
      const deviceList = await window.runnerApp.listAdbDevices();
      setDevices(deviceList);

      if (deviceList.length === 0) {
        setSelectedDeviceId("");
        setDeviceStatus("未检测到 ADB 设备，请确认手表已连接并开启调试；也可以先导出，再把手表切到 USB“传输文件”模式后手动放置 RunnerPlayerExport。");
        return;
      }

      const nextSelectedDeviceId =
        deviceList.find((device) => device.id === selectedDeviceId)?.id ??
        deviceList[0]?.id ??
        "";
      setSelectedDeviceId(nextSelectedDeviceId);
      setDeviceStatus(`已检测到 ${deviceList.length} 台设备，可推送到 /sdcard/Music。`);
    } catch (error) {
      setDeviceStatus(toErrorMessage(error, "读取设备列表失败。若本机未配置调试环境，请直接使用打包版内置 ADB。"));
    } finally {
      setIsRefreshingDevices(false);
    }
  }, [selectedDeviceId]);

  useEffect(() => {
    void refreshAdbDevices();
  }, [refreshAdbDevices]);

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
    if (!selectedDeviceId) {
      setRemoteMusicListing(null);
      return;
    }
    void loadRemoteMusic(selectedDeviceId, REMOTE_MUSIC_ROOT);
  }, [loadRemoteMusic, selectedDeviceId]);

  const pushTracksToDevice = useCallback(async () => {
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
      setDeviceStatus(toErrorMessage(error, "ADB 推送失败。你也可以先导出，再切到 USB“传输文件”模式后手动放置文件。"));
    } finally {
      setIsPushingToDevice(false);
    }
  }, [exportableTracks, libraryName, loadRemoteMusic, selectedDeviceId]);

  const pushRunnerExportToDevice = useCallback(async () => {
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
      await loadRemoteMusic(selectedDeviceId, result.remotePath);
    } catch (error) {
      setDeviceStatus(toErrorMessage(error, "ADB 推送完整曲库失败。你也可以先导出，再切到 USB“传输文件”模式后手动放置文件。"));
    } finally {
      setIsPushingRunnerExport(false);
    }
  }, [exportableTracks, libraryName, loadRemoteMusic, selectedDeviceId]);

  const deleteRemoteMusicEntry = useCallback(async (entry: RemoteMusicEntry) => {
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
  }, [loadRemoteMusic, remoteMusicListing?.currentPath, selectedDeviceId]);

  return {
    devices,
    selectedDeviceId,
    deviceStatus,
    isRefreshingDevices,
    isPushingToDevice,
    isPushingRunnerExport,
    isLoadingRemoteMusic,
    remoteMusicListing,
    pushProgress,
    setSelectedDeviceId,
    refreshAdbDevices,
    pushTracksToDevice,
    pushRunnerExportToDevice,
    loadRemoteMusic,
    deleteRemoteMusicEntry,
  };
}
