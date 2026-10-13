import { useState } from "react";
import { useSuspenseQuery } from "@tanstack/react-query";
import { RefreshCw, Server, ArrowUpRight, Cpu, Network } from "lucide-react";
import { PageHeader } from "@/components/PageHeader";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/StatusBadge";
import { platformService } from "@/services/platform";
import { Sheet, SheetContent, SheetTitle, SheetDescription } from "@/components/ui/sheet";
import type { Worker } from "@/types/platform";
export const workersQuery = { queryKey: ["workers"], queryFn: platformService.workers };
export function WorkerFleet() {
  const { data: workers, refetch, isFetching } = useSuspenseQuery(workersQuery);
  const [selected, setSelected] = useState<Worker | null>(null);
  return (
    <main className="platform-page">
      <PageHeader
        eyebrow="PLATFORM / COMPUTE"
        title="Worker Fleet"
        description="Compute workers, availability, and resource conditions across the platform."
        action={
          <Button variant="outline" size="sm" disabled={isFetching} onClick={() => refetch()}>
            <RefreshCw className={isFetching ? "animate-spin" : ""} />
            Refresh
          </Button>
        }
      />
      <div className="metric-strip">
        {[
          ["Total workers", Server],
          ["Healthy", Network],
          ["Busy", Cpu],
          ["Unavailable", Server],
          ["Fleet latency", Network],
        ].map(([label, Icon]) => {
          const Symbol = Icon as typeof Server;
          return (
            <div key={String(label)}>
              <div className="metric-label">
                {String(label)}
                <Symbol size={14} />
              </div>
              <p className="metric-value">—</p>
              <span className="metric-sub">Not connected</span>
            </div>
          );
        })}
      </div>
      <div className="data-table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              {[
                "WORKER ID",
                "MODEL",
                "CAPABILITY",
                "STATUS",
                "CPU",
                "MEMORY",
                "REQUESTS",
                "LATENCY",
                "",
              ].map((h, i) => (
                <th key={`${h}-${i}`}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {workers.map((w) => (
              <tr key={w.id}>
                <td>
                  <Button variant="link" onClick={() => setSelected(w)}>
                    {w.id}
                  </Button>
                </td>
                <td>{w.model}</td>
                <td>{w.capability}</td>
                <td>
                  <StatusBadge label={w.status} />
                </td>
                <td>{w.cpu === null ? "—" : `${w.cpu}%`}</td>
                <td>{w.memory === null ? "—" : `${w.memory}%`}</td>
                <td>{w.requests ?? "—"}</td>
                <td>{w.latency === null ? "—" : `${w.latency} ms`}</td>
                <td>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={`Inspect worker ${w.id}`}
                    onClick={() => setSelected(w)}
                  >
                    <ArrowUpRight />
                  </Button>
                </td>
              </tr>
            ))}
            {!workers.length && (
              <tr className="table-empty">
                <td colSpan={9}>
                  <Server size={30} className="mx-auto" />
                  <strong>No workers discovered</strong>
                  <p>Not connected to the worker fleet.</p>
                  <span className="font-mono text-xs">—</span>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="table-footer">
        <span>Worker discovery: —</span>
        <span>Last health check: —</span>
      </div>
      <Sheet
        open={Boolean(selected)}
        onOpenChange={(open) => {
          if (!open) setSelected(null);
        }}
      >
        <SheetContent>
          <SheetTitle>{selected?.id}</SheetTitle>
          <SheetDescription>Worker details</SheetDescription>
          {selected &&
            Object.entries(selected).map(([key, value]) => (
              <div className="settings-row" key={key}>
                <span className="capitalize">{key}</span>
                <span>{value ?? "—"}</span>
              </div>
            ))}
        </SheetContent>
      </Sheet>
    </main>
  );
}
