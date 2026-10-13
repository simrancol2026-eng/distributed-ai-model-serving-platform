import { useState } from "react";
import { useSuspenseQuery } from "@tanstack/react-query";
import { Search, RefreshCw, Info, ArrowUpRight, Layers } from "lucide-react";
import { PageHeader } from "@/components/PageHeader";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/StatusBadge";
import { platformService } from "@/services/platform";
import { Dialog, DialogContent, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import type { Model } from "@/types/platform";
export const modelsQuery = { queryKey: ["models"], queryFn: platformService.models };
export function ModelRegistry() {
  const { data, refetch, isFetching } = useSuspenseQuery(modelsQuery);
  // Registry rows are real registrations; examples are a static catalog shown only when the
  // registry cannot be read, and are labelled as examples everywhere they appear.
  const isRegistry = data.source === "registry";
  const models = isRegistry ? data.models : data.examples;
  const [filter, setFilter] = useState("ALL"),
    [search, setSearch] = useState(""),
    [detail, setDetail] = useState<Model | null>(null);
  const visible = models.filter(
    (m) =>
      (filter === "ALL" || m.capability === filter) &&
      m.name.toLowerCase().includes(search.toLowerCase()),
  );
  return (
    <main className="platform-page">
      <PageHeader
        eyebrow="PLATFORM / MODELS"
        title="Model Registry"
        description="Specialized intelligence, unified through one routing layer."
        status={
          isRegistry
            ? { label: "Registry connected", online: true }
            : { label: "Registry unavailable", online: false }
        }
        action={
          <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching}>
            <RefreshCw className={isFetching ? "animate-spin" : ""} />
            Refresh
          </Button>
        }
      />
      <div className="platform-toolbar">
        <div className="filter-tabs">
          {["ALL", "TEXT", "CODE", "IMAGE", "DOCUMENT"].map((c) => (
            <Button
              variant="ghost"
              key={c}
              aria-pressed={filter === c}
              onClick={() => setFilter(c)}
            >
              {c === "ALL" ? "All models" : c}
            </Button>
          ))}
        </div>
        <label className="search-field">
          <Search size={13} />
          <input
            aria-label="Search models"
            placeholder="Search models"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
      </div>
      {isRegistry ? (
        <p className="catalog-note" data-testid="registry-note">
          <Info size={12} />
          Registered models from the platform registry · Registered means recorded, not deployed or
          running.
        </p>
      ) : (
        <p className="catalog-note" role="status" data-testid="examples-note">
          <Info size={12} />
          Registry unavailable ({data.reason}). The rows below are EXAMPLE data only — they are not
          registered models.
        </p>
      )}
      <div className="data-table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              {["MODEL", "CAPABILITY", "VERSION", "WORKERS", "STATUS", ""].map((h, i) => (
                <th key={`${h}-${i}`}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {visible.map((m) => (
              <tr key={m.id}>
                <td>
                  <div className="model-cell">
                    <span className="model-monogram">{m.name.slice(0, 2).toUpperCase()}</span>
                    <div>
                      <strong>{m.name}</strong>
                      <small>{m.id}</small>
                    </div>
                  </div>
                </td>
                <td>{m.task}</td>
                <td className="font-mono text-muted-foreground">{m.version ?? "—"}</td>
                <td className="font-mono text-muted-foreground">{m.workers ?? "—"}</td>
                <td>
                  <StatusBadge label={isRegistry ? m.status : "Example only"} />
                </td>
                <td>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={`Inspect ${m.name}`}
                    title={`Inspect ${m.name}`}
                    onClick={() => setDetail(m)}
                  >
                    <ArrowUpRight />
                  </Button>
                </td>
              </tr>
            ))}
            {!visible.length && (
              <tr className="table-empty">
                <td colSpan={6}>
                  <Layers size={24} className="mx-auto" />
                  {isRegistry && !models.length ? (
                    <>
                      <strong>No models registered yet</strong>
                      <p>Register one with POST /api/models (see scripts/seed_registry.py).</p>
                    </>
                  ) : (
                    <>
                      <strong>No matching models</strong>
                      <p>Change the capability or search.</p>
                    </>
                  )}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="table-footer">
        <span>
          {visible.length}{" "}
          {isRegistry
            ? `registered model${visible.length === 1 ? "" : "s"}`
            : `example entr${visible.length === 1 ? "y" : "ies"}`}
        </span>
        <span>Registry connection: {isRegistry ? "connected" : "unavailable"}</span>
      </div>
      <Dialog
        open={Boolean(detail)}
        onOpenChange={(open) => {
          if (!open) setDetail(null);
        }}
      >
        <DialogContent>
          <DialogTitle>{detail?.name}</DialogTitle>
          <DialogDescription>
            {isRegistry
              ? `Registered model · ${detail?.id ?? ""}`
              : "Example entry · not a registered model"}
          </DialogDescription>
          {[
            ["Capability", detail?.capability],
            ["Task", detail?.task],
            ["Latest registered version", detail?.version],
            ["Workers", detail?.workers],
            ["Status", isRegistry ? detail?.status : "Example only"],
            ["Description", detail?.description],
          ].map(([key, value]) => (
            <div className="settings-row" key={key}>
              <span>{key}</span>
              <span>{value ?? "—"}</span>
            </div>
          ))}
        </DialogContent>
      </Dialog>
    </main>
  );
}
