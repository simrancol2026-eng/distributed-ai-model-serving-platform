import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

export function Demo({ className }: { className?: string }) {
  return (
    <span className={cn("micro inline-flex items-center gap-1 rounded-[3px] border border-dashed hairline px-1.5 py-0.5 !text-[9px]", className)}>
      <span className="size-1 rounded-full bg-muted-foreground" /> Demo data
    </span>
  );
}

export function Status({ tone = "idle", children }: { tone?: "ok" | "idle" | "off"; children: ReactNode }) {
  return (
    <span className="micro inline-flex items-center gap-1.5">
      <span className={cn("size-1.5 rounded-full", tone === "ok" ? "bg-leaf blink" : tone === "off" ? "border border-muted-foreground" : "bg-muted-foreground")} />
      {children}
    </span>
  );
}

export function Panel({ title, right, children, className }: { title?: ReactNode; right?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-[6px] border hairline p-4 transition-colors hover:border-foreground/20", className)}>
      {(title || right) && (
        <header className="mb-3 flex items-center justify-between gap-2">
          <span className="micro">{title}</span>
          {right}
        </header>
      )}
      {children}
    </section>
  );
}

export function Metric({ label, value, unit, note }: { label: string; value: ReactNode; unit?: string; note?: ReactNode }) {
  return (
    <div className="group">
      <div className="micro mb-1">{label}</div>
      <div className="num text-4xl text-foreground transition-colors group-hover:text-leaf lg:text-[44px]">
        {value}
        {unit && <span className="ml-1 text-base text-muted-foreground">{unit}</span>}
      </div>
      {note && <div className="mt-1 text-[11px] text-muted-foreground">{note}</div>}
      <div className="mt-4 h-px w-8 bg-border" />
    </div>
  );
}

const seeded = (n: number, s: number) => Array.from({ length: n }, (_, i) => Math.abs(Math.sin(i * 12.9898 + s * 78.233) * 43758.5453) % 1);

export function Bars({ n = 24, seed = 1, h = 64, accentEvery = 0 }: { n?: number; seed?: number; h?: number; accentEvery?: number }) {
  const v = seeded(n, seed);
  return (
    <div className="flex items-end gap-[3px]" style={{ height: h }}>
      {v.map((x, i) => (
        <div
          key={i}
          className={cn("w-[3px] rounded-[1px] transition-all hover:bg-foreground", accentEvery && i % accentEvery === accentEvery - 1 ? "bg-leaf" : "bg-foreground/30")}
          style={{ height: `${12 + x * 88}%` }}
        />
      ))}
    </div>
  );
}

export function Timeline() {
  const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  const rows = [seeded(56, 3), seeded(56, 7), seeded(56, 11)];
  return (
    <div className="border-t hairline pt-4">
      <div className="mb-3 flex items-center justify-between">
        <span className="micro">Inference activity / week</span>
        <Demo />
      </div>
      <div className="space-y-1.5 overflow-hidden">
        {rows.map((r, ri) => (
          <div key={ri} className="flex gap-[3px]">
            {r.map((x, i) => (
              <div
                key={i}
                className={cn("h-2 flex-1 rounded-[1px] transition-colors hover:bg-foreground", x > 0.82 ? "bg-leaf" : x > 0.45 ? "bg-foreground/25" : "bg-foreground/[0.07]")}
              />
            ))}
          </div>
        ))}
      </div>
      <div className="mt-2 flex justify-between">
        {days.map((d) => <span key={d} className="micro">{d}</span>)}
      </div>
    </div>
  );
}

export function Tabs<T extends string>({ value, options, onChange }: { value: T; options: readonly T[]; onChange: (v: T) => void }) {
  return (
    <div className="flex gap-5 border-b hairline" role="tablist">
      {options.map((o) => (
        <button
          key={o}
          role="tab"
          aria-selected={o === value}
          onClick={() => onChange(o)}
          className={cn("-mb-px border-b pb-2 text-[13px] transition-colors", o === value ? "border-foreground text-foreground" : "border-transparent text-muted-foreground hover:text-foreground")}
        >
          {o}
        </button>
      ))}
    </div>
  );
}
