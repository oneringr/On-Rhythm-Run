import type { LibraryStats } from "../hooks/useLibraryScan";
import { SummaryCard } from "./SummaryCard";

type StatsBarProps = {
  stats: LibraryStats;
};

export function StatsBar({ stats }: StatsBarProps) {
  return (
    <section className="panel-card">
      <div className="summary-grid">
        <SummaryCard label="总数" value={stats.total.toString()} accent="mint" />
        <SummaryCard label="待导出" value={stats.exportable.toString()} accent="mint" />
        <SummaryCard label="已排除" value={stats.excluded.toString()} accent="salmon" />
        <SummaryCard label="舒缓" value={stats.calm.toString()} accent="mint" />
        <SummaryCard label="激动" value={stats.excited.toString()} accent="salmon" />
      </div>
    </section>
  );
}
