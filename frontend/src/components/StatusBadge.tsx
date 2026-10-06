import { Circle } from 'lucide-react';
export function StatusBadge({ label = 'Not connected', compact = false }: { label?: string; compact?: boolean }) {
 return <span className={`status-badge ${compact ? 'compact' : ''}`}><Circle size={6} className="fill-accent text-accent" /><span>{label}</span></span>;
}
