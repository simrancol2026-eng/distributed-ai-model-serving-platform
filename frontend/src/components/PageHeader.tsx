import type { ReactNode } from "react";
import { StatusBadge } from "./StatusBadge";
export function PageHeader({
  eyebrow,
  title,
  description,
  action,
  status,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
  /** Page-specific connection badge; pages without a backend feature keep "Not connected". */
  status?: { label: string; online: boolean };
}) {
  return (
    <header className="page-heading">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p className="page-description">{description}</p>
      </div>
      <div className="page-heading-actions">
        {action}
        {status ? <StatusBadge label={status.label} online={status.online} /> : <StatusBadge />}
      </div>
    </header>
  );
}
