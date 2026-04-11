import { memo } from "react";
import type { AnalyzedTrack } from "../../common/manifest";
import { toChineseLabel } from "../utils/formatting";

type TrackRowProps = {
  track: AnalyzedTrack;
  isActive: boolean;
  onPreview: (track: AnalyzedTrack) => Promise<void>;
  onLabelChange: (trackId: string, finalLabel: "calm" | "excited") => void;
  onToggleExclude: (trackId: string) => void;
};

export const TrackRow = memo(function TrackRow({
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
