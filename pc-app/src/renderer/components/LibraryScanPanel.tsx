import type { ScanProgressUpdate } from "../../common/manifest";

type LibraryScanPanelProps = {
  libraryName: string;
  sourceFolder: string;
  exportFolder: string;
  status: string;
  isScanning: boolean;
  scanProgress: ScanProgressUpdate | null;
  onLibraryNameChange: (value: string) => void;
  onChooseSourceFolder: () => void | Promise<void>;
  onChooseExportFolder: () => void | Promise<void>;
  onScanLibrary: () => void | Promise<void>;
  onExportLibrary: () => void | Promise<void>;
};

export function LibraryScanPanel({
  libraryName,
  sourceFolder,
  exportFolder,
  status,
  isScanning,
  scanProgress,
  onLibraryNameChange,
  onChooseSourceFolder,
  onChooseExportFolder,
  onScanLibrary,
  onExportLibrary,
}: LibraryScanPanelProps) {
  const isWarning = status.includes("没有找到") || status.includes("仅发现");

  return (
    <>
      <section className="panel-card">
        <label className="field-label" htmlFor="libraryName">
          曲库名称
        </label>
        <input
          id="libraryName"
          className="text-field"
          value={libraryName}
          onChange={(event) => onLibraryNameChange(event.target.value)}
        />

        <div className="button-row">
          <button className="primary-button" onClick={onChooseSourceFolder}>
            选择音乐目录
          </button>
          <button
            className="secondary-button"
            onClick={onScanLibrary}
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

        <div className={`scan-feedback ${isWarning ? "warning" : ""}`}>
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
        <div className="button-row">
          <button className="primary-button" onClick={onChooseExportFolder}>
            选择导出目录
          </button>
          <button className="secondary-button" onClick={onExportLibrary}>
            导出曲库
          </button>
        </div>

        <div className="path-block">
          <span className="field-label">导出目录</span>
          <span>{exportFolder || "尚未选择导出目录"}</span>
          <span className="support-hint">
            如果不通过 ADB 推送，导出完成后可把手表切换到 USB“传输文件”模式，
            再手动将 <code>RunnerPlayerExport</code> 放到 <code>/sdcard/Music</code> 下。
          </span>
        </div>
      </section>
    </>
  );
}
