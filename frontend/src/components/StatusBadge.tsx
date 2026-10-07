import { Circle } from "lucide-react";
export function StatusBadge({
  label = "Not connected",
  compact = false,
  online = false,
}: {
  label?: string;
  compact?: boolean;
  online?: boolean;
}) {
  const dot = online ? "fill-primary text-primary" : "fill-accent text-accent";
  return (
    <span className={`status-badge ${compact ? "compact" : ""}`}>
      <Circle size={6} className={dot} />
      <span>{label}</span>
    </span>
  );
}
