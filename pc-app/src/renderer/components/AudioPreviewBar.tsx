import type { RefObject } from "react";
import { formatDuration } from "../utils/formatting";

type AudioPreviewBarProps = {
  audioRef: RefObject<HTMLAudioElement>;
  previewTitle: string;
  previewDuration: number;
  previewPosition: number;
  isPreviewPlaying: boolean;
  hasPreviewTrack: boolean;
  activePreviewTrackId: string;
  autoAdvanceOnLabelChange: boolean;
  onLocateActivePreviewTrack: () => void;
  onTogglePreviewPlayback: () => void | Promise<void>;
  onSeekPreview: (nextPosition: number) => void;
  onStartPreviewScrub: () => void;
  onFinishPreviewScrub: () => void;
  onPreviewError: () => void;
};

export function AudioPreviewBar({
  audioRef,
  previewTitle,
  previewDuration,
  previewPosition,
  isPreviewPlaying,
  hasPreviewTrack,
  activePreviewTrackId,
  autoAdvanceOnLabelChange,
  onLocateActivePreviewTrack,
  onTogglePreviewPlayback,
  onSeekPreview,
  onStartPreviewScrub,
  onFinishPreviewScrub,
  onPreviewError,
}: AudioPreviewBarProps) {
  return (
    <section className={`floating-preview-bar ${hasPreviewTrack ? "active" : ""}`}>
      <audio
        ref={audioRef}
        preload="metadata"
        className="hidden-audio"
        onError={onPreviewError}
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
            onClick={onLocateActivePreviewTrack}
            disabled={!hasPreviewTrack}
          >
            定位当前
          </button>
          <button
            className="primary-button floating-preview-button"
            onClick={() => void onTogglePreviewPlayback()}
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
          onPointerDown={onStartPreviewScrub}
          onPointerUp={onFinishPreviewScrub}
          onBlur={onFinishPreviewScrub}
          onInput={(event) => onSeekPreview(Number(event.currentTarget.value))}
          onChange={(event) => onSeekPreview(Number(event.currentTarget.value))}
          disabled={!hasPreviewTrack}
        />
        <span>{formatDuration(previewDuration)}</span>
      </div>
    </section>
  );
}
