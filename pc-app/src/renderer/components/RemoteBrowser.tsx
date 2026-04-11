import type { RemoteMusicEntry, RemoteMusicListing } from "../../common/manifest";
import { REMOTE_MUSIC_ROOT } from "../config";

type RemoteBrowserProps = {
  selectedDeviceId: string;
  isLoadingRemoteMusic: boolean;
  remoteMusicListing: RemoteMusicListing | null;
  onLoadRemoteMusic: (deviceId: string, remotePath: string) => void | Promise<void>;
  onDeleteRemoteEntry: (entry: RemoteMusicEntry) => void | Promise<void>;
};

export function RemoteBrowser({
  selectedDeviceId,
  isLoadingRemoteMusic,
  remoteMusicListing,
  onLoadRemoteMusic,
  onDeleteRemoteEntry,
}: RemoteBrowserProps) {
  return (
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
              onLoadRemoteMusic(selectedDeviceId, remoteMusicListing.parentPath)
            }
            disabled={!selectedDeviceId || !remoteMusicListing?.parentPath || isLoadingRemoteMusic}
          >
            上一级
          </button>
          <button
            className="ghost-button"
            onClick={() =>
              selectedDeviceId &&
              onLoadRemoteMusic(selectedDeviceId, remoteMusicListing?.currentPath ?? REMOTE_MUSIC_ROOT)
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
                    onClick={() => selectedDeviceId && onLoadRemoteMusic(selectedDeviceId, entry.path)}
                  >
                    打开
                  </button>
                ) : null}
                <button className="danger-button" onClick={() => void onDeleteRemoteEntry(entry)}>
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
  );
}
