export function SummaryCard(props: { label: string; value: string; accent: "mint" | "salmon" }) {
  return (
    <div className={`summary-card ${props.accent}`}>
      <span>{props.label}</span>
      <strong>{props.value}</strong>
    </div>
  );
}
