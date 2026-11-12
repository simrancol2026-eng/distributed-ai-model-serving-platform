import { useEffect, useState } from "react";
import { api, ENDPOINTS, isApiConfigured, API_BASE_URL, type Capability, type InferenceResult } from "@/lib/api";
import { cn } from "@/lib/utils";
import { InfraCube } from "./Cube";
import { Bars, Demo, Metric, Panel, Status, Tabs, Timeline } from "./ui";

/* ---------- Demo fixtures (replace with API responses) ---------- */
const DEMO_WORKERS = [
  { id: "worker-01", host: "10.0.1.11:8081", model: "distilbert-sst2", inFlight: 3, p50: 41, state: "healthy" },
  { id: "worker-02", host: "10.0.1.12:8081", model: "distilbert-sst2", inFlight: 1, p50: 38, state: "healthy" },
  { id: "worker-03", host: "10.0.1.13:8081", model: "distilbert-sst2", inFlight: 5, p50: 57, state: "degraded" },
  { id: "worker-04", host: "10.0.1.14:8081", model: "distilbert-sst2", inFlight: 0, p50: 0, state: "draining" },
];

export const MODELS = [
  { name: "Text Sentiment", id: "distilbert-base-uncased-sst2", kind: "NLP · classification", status: "available" as const, note: "TEXT capability via POST /api/inference" },
  { name: "Code Language", id: "CodeBERTa", kind: "Code · classification", status: "configured" as const, note: "Requires a CODE worker configured in Spring Boot." },
  { name: "Image Classify", id: "ViT", kind: "Vision · classification", status: "configured" as const, note: "Requires an IMAGE worker configured in Spring Boot." },
];

/* ---------- Home: My deployments / Overview / Workers ---------- */
const HOME_TABS = ["My deployments", "Overview", "Workers"] as const;

export function HomeScreen() {
  const [tab, setTab] = useState<(typeof HOME_TABS)[number]>("My deployments");
  return (
    <div>
      <Tabs value={tab} options={HOME_TABS} onChange={setTab} />
      <div className="pt-6" key={tab}>
        <div className="rise">
          {tab === "My deployments" && <Deployments />}
          {tab === "Overview" && <OverviewBoard />}
          {tab === "Workers" && <WorkersTable />}
        </div>
      </div>
    </div>
  );
}

function Deployments() {
  return (
    <>
      <div className="grid gap-8 lg:grid-cols-[220px_1fr_220px]">
        <aside className="space-y-6">
          <div className="group relative aspect-square w-32 rounded-[4px] border border-leaf/25 bg-deep p-3 transition-colors hover:border-leaf/50">
            <div className="micro !text-foreground/80">System health</div>
            <div className="num absolute bottom-2 left-3 text-4xl text-foreground">—%</div>
            <Demo className="absolute right-2 top-7 !text-foreground/70" />
          </div>
          <p className="text-[11px] leading-relaxed text-muted-foreground">Placeholder until {ENDPOINTS.health} is reachable.</p>
          <Metric label="Registered workers" value={DEMO_WORKERS.length} note={<Demo />} />
          <Metric label="Models served" value="1" unit="/ 3" note="2 planned" />
        </aside>
        <div className="relative flex min-h-[420px] items-center justify-center lg:min-h-[560px]">
          <div className="absolute left-0 top-0 micro">Cluster topology</div>
          <div className="absolute right-0 top-0"><Demo /></div>
          <InfraCube size={760} />
          <div className="absolute bottom-0 left-0 micro">Gateway → load balancer → workers</div>
        </div>
        <aside className="space-y-6 lg:text-right">
          <Metric label="Requests / min" value="1,284" note={<Demo />} />
          <Metric label="p50 latency" value="42" unit="ms" note={<Demo />} />
          <Metric label="In-flight" value="9" note={<Demo />} />
        </aside>
      </div>
      <div className="mt-8"><Timeline /></div>
    </>
  );
}

function OverviewBoard() {
  const charts = [
    { t: "Throughput", v: "1.2k", u: "req/min", s: 2 },
    { t: "Latency p95", v: "88", u: "ms", s: 5 },
    { t: "Error rate", v: "0.4", u: "%", s: 9 },
  ];
  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_280px]">
      <div className="space-y-4">
        <div className="grid gap-4 md:grid-cols-3">
          {charts.map((c) => (
            <Panel key={c.t} title={c.t} right={<Demo />}>
              <div className="num mb-4 text-3xl">{c.v}<span className="ml-1 text-sm text-muted-foreground">{c.u}</span></div>
              <Bars seed={c.s} n={30} h={70} accentEvery={7} />
            </Panel>
          ))}
        </div>
        <div className="grid gap-4 md:grid-cols-3">
          <div className="rounded-[6px] border hairline bg-card p-4 text-foreground transition-transform hover:-translate-y-0.5">
            <div className="micro !text-muted-foreground">Active model</div>
            <div className="num mt-6 text-2xl">DistilBERT</div>
            <div className="mt-1 text-[12px] opacity-70">Text sentiment · SST-2</div>
          </div>
          <Panel title="Routing" right={<Demo />}>
            <div className="num text-2xl">Server-chosen</div>
            <div className="mt-2"><Status tone="idle">See routing evidence per request</Status></div>
          </Panel>
          <Panel title="Worker pool" right={<Demo />}>
            <div className="flex gap-1.5">
              {DEMO_WORKERS.map((w) => (
                <span key={w.id} className={cn("h-6 flex-1 rounded-[2px]", w.state === "healthy" ? "bg-leaf/70" : w.state === "degraded" ? "bg-foreground/30" : "border hairline")} />
              ))}
            </div>
            <div className="micro mt-3">2 healthy · 1 degraded · 1 draining</div>
          </Panel>
        </div>
      </div>
      <Panel title="Recommendations" right={<Demo />}>
        <ul className="space-y-4 text-[13px]">
          {[
            ["Switch to Least In-Flight", "worker-03 shows elevated queue depth."],
            ["Drain worker-04", "No traffic in the last 15 min."],
            ["Metrics unavailable", "No metrics endpoint in the current API contract."],
          ].map(([a, b]) => (
            <li key={a} className="border-b hairline pb-3 last:border-0">
              <div className="flex items-center gap-2"><span className="size-1 rounded-full bg-leaf" />{a}</div>
              <div className="mt-1 pl-3 text-[11px] text-muted-foreground">{b}</div>
            </li>
          ))}
        </ul>
      </Panel>
    </div>
  );
}

export function WorkersTable() {
  return (
    <Panel title="Workers · no endpoint yet" right={<Demo />}>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] text-left text-[13px]">
          <thead>
            <tr className="micro">{["Worker", "Host", "Model", "In-flight", "p50", "State"].map((h) => <th key={h} className="pb-2 font-normal">{h}</th>)}</tr>
          </thead>
          <tbody>
            {DEMO_WORKERS.map((w) => (
              <tr key={w.id} className="border-t hairline transition-colors hover:bg-foreground/[0.03]">
                <td className="py-2.5 font-mono text-[12px]">{w.id}</td>
                <td className="font-mono text-[12px] text-muted-foreground">{w.host}</td>
                <td className="text-muted-foreground">{w.model}</td>
                <td className="num">{w.inFlight}</td>
                <td className="num">{w.p50 ? `${w.p50}ms` : "—"}</td>
                <td><Status tone={w.state === "healthy" ? "ok" : w.state === "draining" ? "off" : "idle"}>{w.state}</Status></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}

/* ---------- Models ---------- */
export function ModelsScreen() {
  return (
    <div className="grid gap-4 md:grid-cols-3">
      {MODELS.map((m) => (
        <Panel key={m.name} title={m.kind} right={<Status tone="off">Worker required</Status>}>
          <div className="num mt-6 text-3xl">{m.name}</div>
          <div className="mt-2 font-mono text-[11px] text-muted-foreground">{m.id}</div>
          <div className="mt-8 border-t hairline pt-3 text-[12px] text-muted-foreground">{m.note}</div>
        </Panel>
      ))}
    </div>
  );
}

/* ---------- Inference console ---------- */
export function useHealth() {
  const [state, setState] = useState<"unset" | "checking" | "ok" | "down">(isApiConfigured() ? "checking" : "unset");
  const [detail, setDetail] = useState<string>("");
  const check = async () => {
    if (!isApiConfigured()) return;
    setState("checking");
    try { setDetail((await api.health()).slice(0, 80)); setState("ok"); } catch (e) { setDetail((e as Error).message); setState("down"); }
  };
  useEffect(() => { void check(); }, []);
  return { state, detail, check };
}

const CAPS = [
  { id: "TEXT", label: "Text", on: true },
  { id: "CODE", label: "Code language", on: true },
  { id: "IMAGE", label: "Image", on: true },
] as const;

export function InferenceScreen() {
  const health = useHealth();
  const [capability, setCapability] = useState<Capability>("TEXT");
  const [text, setText] = useState("The new routing layer made our latency drop dramatically.");
  const [res, setRes] = useState<InferenceResult | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true); setErr(null); setRes(null);
    try { setRes(await api.inference(text, undefined, capability)); } catch (e) { setErr((e as Error).message); } finally { setBusy(false); }
  };
  const rt = res?.routing;
  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
      <Panel title={`Inference · ${capability}`} right={<span className="micro">POST {ENDPOINTS.inference}</span>}>
        <div className="mb-3 flex flex-wrap gap-2">
          {CAPS.map((c) => (
            <button key={c.id} type="button" disabled={busy} aria-pressed={capability === c.id}
              onClick={() => { setCapability(c.id); setText(""); setRes(null); setErr(null); }}
              className={cn("rounded-[3px] border px-2 py-0.5 text-[12px]", capability === c.id ? "border-leaf/60 text-leaf" : "hairline text-muted-foreground")}>{c.label}</button>
          ))}
        </div>
        <textarea
          aria-label={capability === "IMAGE" ? "PNG or JPEG data URL or base64" : capability === "CODE" ? "Code snippet" : "Text for sentiment analysis"}
          placeholder={capability === "IMAGE" ? "Paste a PNG or JPEG data URL or base64 image" : capability === "CODE" ? "Paste a code snippet" : "Enter text for sentiment analysis"}
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={6}
          className="w-full resize-none rounded-[4px] border hairline bg-transparent p-3 text-[14px] outline-none focus:border-foreground/30"
        />
        <div className="mt-3 flex items-center justify-between gap-3">
          <Status tone={health.state === "ok" ? "ok" : health.state === "unset" ? "off" : "idle"}>
            {health.state === "ok" ? "Connected" : health.state === "checking" ? "Checking…" : health.state === "down" ? "Unreachable" : "Not connected"}
          </Status>
          <button onClick={run} disabled={busy || !text.trim() || !isApiConfigured()} className="rounded-[3px] border border-leaf/60 px-4 py-1.5 text-[13px] text-leaf transition-colors hover:bg-leaf hover:text-primary-foreground disabled:opacity-40">
            {busy ? "Running…" : "Run inference"}
          </button>
        </div>
        {!isApiConfigured() && <p className="mt-3 text-[12px] text-muted-foreground">Not connected — set VITE_API_BASE_URL to send real requests.</p>}
        {health.state === "down" && <p className="mt-3 font-mono text-[11px] text-destructive">{health.detail}</p>}
      </Panel>
      <Panel title="Response">
        {res ? (
          <div className="space-y-4">
            <div>
              <div className="num text-4xl">{res.prediction.label}</div>
              <div className="micro mt-2">score {res.prediction.score.toFixed(3)} · {res.totalLatencyMs}ms</div>
            </div>
            <dl className="grid grid-cols-[90px_1fr] gap-y-1 font-mono text-[11px]">
              {([["model", `${res.model} ${res.version}`], ["worker", res.worker], ["endpoint", res.workerEndpoint], ["request", res.requestId]] as const).map(([k, v]) => (
                <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="break-all">{v}</dd></div>
              ))}
            </dl>
            {rt && (
              <div className="border-t hairline pt-3">
                <div className="micro mb-2">Routing evidence</div>
                <dl className="grid grid-cols-[90px_1fr] gap-y-1 font-mono text-[11px]">
                  {([["policy", rt.policy], ["chosen", rt.chosen], ["reason", rt.reason],
                    ...(rt.attempts != null ? [["attempts", typeof rt.attempts === "number" ? String(rt.attempts) : JSON.stringify(rt.attempts)]] : []),
                    ...(rt.terminalReason ? [["terminal", rt.terminalReason]] : [])] as [string, string][]).map(([k, v]) => (
                    <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="break-all">{v}</dd></div>
                  ))}
                </dl>
              </div>
            )}
          </div>
        ) : err ? (
          <p className="font-mono text-[12px] text-destructive">{err}</p>
        ) : (
          <p className="text-[12px] text-muted-foreground">No request yet.</p>
        )}
      </Panel>
    </div>
  );
}

/* ---------- Routing ---------- */
const ALGOS = [
  { name: "Round Robin", desc: "Cycle requests evenly across healthy workers." },
  { name: "Least In-Flight", desc: "Send to the worker with the fewest active requests." },
  { name: "Weighted", desc: "Not supported by the backend." },
  { name: "Health and Latency", desc: "Select a healthy eligible worker using estimated latency and workload." },
];

export function RoutingScreen() {
  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_300px]">
      <div className="grid gap-3 sm:grid-cols-2">
        {ALGOS.map((a) => (
          <div key={a.name} className="rounded-[6px] border hairline p-4 opacity-60">
            <span className="micro">Read-only</span>
            <div className="num mt-5 text-2xl">{a.name}</div>
            <div className="mt-1 text-[12px] text-muted-foreground">{a.desc}</div>
          </div>
        ))}
      </div>
      <Panel title="Strategy changes">
        <div className="num text-2xl">Unavailable</div>
        <p className="mt-3 text-[12px] text-muted-foreground">The API has no routing mutation endpoint. The policy the server actually used appears as routing evidence on each inference response.</p>
      </Panel>
    </div>
  );
}

/* ---------- Monitoring ---------- */
const LOGS = [
  ["15:52:04.112", "INFO", "gateway", "route POST /sentiment → worker-02 (round_robin)"],
  ["15:52:04.087", "INFO", "worker-02", "inference ok label=POSITIVE 39ms"],
  ["15:52:03.951", "WARN", "worker-03", "queue depth 5 exceeds soft limit 4"],
  ["15:52:03.420", "INFO", "registry", "heartbeat worker-01 healthy"],
  ["15:52:02.877", "INFO", "worker-04", "drain requested; rejecting new work"],
];

export function MonitoringScreen() {
  const h = useHealth();
  const health = h.state === "ok" ? "Connected" : h.state === "down" ? "Unreachable" : h.state === "checking" ? "Checking…" : null;
  return (
    <div className="space-y-4">
      <div className="grid gap-4 md:grid-cols-3">
        <Panel title="Health check" right={<span className="micro">GET {ENDPOINTS.health}</span>}>
          <div className="num text-3xl">{health ?? "—"}</div>
          <div className="mt-1 font-mono text-[11px] text-muted-foreground">{API_BASE_URL ?? "VITE_API_BASE_URL unset"}</div>
          {h.detail && <div className="mt-1 break-all font-mono text-[11px] text-muted-foreground">{h.detail}</div>}
          <button onClick={() => void h.check()} disabled={!isApiConfigured()} className="mt-4 rounded-[3px] border hairline px-3 py-1 text-[12px] transition-colors hover:border-foreground/40">Ping</button>
        </Panel>
        <Panel title="CPU / worker" right={<Demo />}><Bars seed={4} n={32} h={70} accentEvery={5} /></Panel>
        <Panel title="Memory / worker" right={<Demo />}><Bars seed={8} n={32} h={70} accentEvery={9} /></Panel>
      </div>
      <Panel title="Logs" right={<Demo />}>
        <div className="space-y-1 font-mono text-[12px]">
          {LOGS.map((l) => (
            <div key={l[0]} className="flex gap-4 rounded-[2px] px-1 py-0.5 hover:bg-foreground/[0.03]">
              <span className="text-muted-foreground">{l[0]}</span>
              <span className={l[1] === "WARN" ? "text-chart-5" : "text-leaf"}>{l[1]}</span>
              <span className="w-20 shrink-0 text-muted-foreground">{l[2]}</span>
              <span className="truncate">{l[3]}</span>
            </div>
          ))}
        </div>
      </Panel>
      <WorkersTable />
    </div>
  );
}
