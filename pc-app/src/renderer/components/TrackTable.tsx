import type { AnalyzedTrack } from "../../common/manifest";
import { TrackRow } from "./TrackRow";

type TrackTableProps = {
  tracks: AnalyzedTrack[];
  activePreviewTrackId: string;
  onPreview: (track: AnalyzedTrack) => Promise<void>;
  onLabelChange: (trackId: string, finalLabel: "calm" | "excited") => void;
  onToggleExclude: (trackId: string) => void;
};

export function TrackTable({
  tracks,
  activePreviewTrackId,
  onPreview,
  onLabelChange,
  onToggleExclude,
}: TrackTableProps) {
  return (
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
            onPreview={onPreview}
            onLabelChange={onLabelChange}
            onToggleExclude={onToggleExclude}
          />
        ))}

        {tracks.length === 0 ? (
          <div className="empty-state">
            还没有分析结果。请选择目录后开始扫描。
          </div>
        ) : null}
      </div>
    </main>
  );
}
