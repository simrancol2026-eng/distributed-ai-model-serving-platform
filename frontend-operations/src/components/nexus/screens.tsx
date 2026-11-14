import { useEffect, useState } from "react";
import { api, ENDPOINTS, isApiConfigured, type Capability, type Task, type InferenceResult, type Capabilities, type TrackedRequest, type RoutingEvidence, type WorkerView, ApiError } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Metric, Panel, Status, Tabs } from "./ui";

export function workerState(w: WorkerView, capability: string) {
  if (!w.reachable) return "Unreachable";
  if (!w.healthy) return "Unhealthy";
  if (w.reportedCapability !== capability) return "Capability mismatch";
  return "Healthy";
}
export function failoverState(routing?: RoutingEvidence | null) {
  const attempts = routing?.attempts;
  if (!Array.isArray(attempts)) return "Attempt evidence unavailable";
  if (attempts.length < 2) return "No retry recorded";
  if (new Set(attempts.map(a => a.endpoint)).size < 2) return "No alternate worker recorded";
  return attempts.at(-1)?.outcome === "SUCCEEDED" ? "Recovered on another worker" : "Alternate worker attempted";
}
function useLive<T>(fetcher: () => Promise<T>) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [checked, setChecked] = useState("");
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const refresh = async () => {
      try {
        if (!isApiConfigured()) throw new Error("API not configured");
        const result = await fetcher();
        if (active) { setData(result); setError(""); setChecked(new Date().toLocaleTimeString()); }
      } catch (e) {
        if (active) { setData(null); setError((e as Error).message); setChecked(""); }
      } finally { if (active) timer = setTimeout(refresh, 5000); }
    };
    void refresh();
    return () => { active = false; clearTimeout(timer); };
  }, [fetcher]);
  return { data, error, checked };
}
const getCapabilities = () => api.capabilities();
const getRequests = () => api.requests();
function Availability({ error, checked }: { error: string; checked: string }) {
  return <p className="mb-4 text-xs text-muted-foreground" role="status">
    {error ? "Unavailable - " + error : checked ? "Last completed check " + checked + " � refreshes every 5 seconds" : "Checking."}
  </p>;
}
export function RoutingDetails({ routing }: { routing: RoutingEvidence }) {
  return <div className="space-y-2 border-t hairline pt-3 text-xs">
    <p>Policy: {routing.policy ?? "Unavailable"} � eligible: {routing.eligibleCount ?? "-"} / {routing.poolSize ?? "-"}</p>
    <p>{routing.reason}</p><p>Selected endpoint: {routing.chosen ?? "None"}</p>
    <p>{failoverState(routing)}</p>
    {routing.attempts?.map((a, i) => <div key={i} className="rounded border hairline p-2">
      Attempt {i + 1}: {a.endpoint}  {a.outcome} � {a.elapsedMs} ms {a.failureClass && <span> � {a.failureClass}</span>}
    </div>)}
    {routing.terminalReason && <p>Final reason: {routing.terminalReason}</p>}
    {!!routing.candidates?.length && <details><summary>Workers considered at dispatch</summary>
      {routing.candidates.map(c => <p key={c.endpoint}>{c.endpoint} � {c.note} � in-flight at selection: {c.inFlight ?? "Unavailable"}</p>)}
    </details>}
  </div>;
}
const HOME_TABS = ["My deployments", "Overview", "Workers"] as const;
export function HomeScreen() {
  const [tab, setTab] = useState<(typeof HOME_TABS)[number]>("My deployments");
  return <div><Tabs value={tab} options={HOME_TABS} onChange={setTab} />
    <div className="space-y-4 pt-6">{tab === "Workers" ? <WorkersTable /> : <><OperationsSummary /><WorkersTable />{tab === "Overview" && <RequestEvidence />}</>}</div>
  </div>;
}
function OperationsSummary() {
  const live = useLive<Capabilities>(getCapabilities);
  const pools = live.data?.capabilities;
  const configured = pools?.reduce((n, p) => n + p.workerCount, 0);
  const healthy = pools?.reduce((n, p) => n + p.healthyCount, 0);
  const models = pools ? new Set(pools.flatMap(p => p.workers.filter(w => workerState(w, p.capability) === "Healthy" && w.model && w.revision).map(w => w.model + "@" + w.revision))).size : null;
  return <Panel title="Live operations"><Availability {...live} />
    <div className="grid gap-6 md:grid-cols-3">
      <Metric label="Configured pool entries" value={configured ?? "-"} note="A URL can appear in more than one pool" />
      <Metric label="Healthy matching entries" value={healthy ?? "-"} note="Reachable, healthy and correct capability" />
      <Metric label="Observed model revisions" value={models ?? "-"} note="From healthy matching workers" />
    </div>
    <div className="mt-5 grid gap-4 md:grid-cols-3 text-sm">
      <p>Load balancing: {pools?.map(p => p.capability + ": " + p.policy).join(" � ") || "Status unavailable"}</p>
      <p>Failover: {live.data?.failoverEnabled == null ? "Status unavailable" : live.data.failoverEnabled ? "Enabled" : "Disabled"}{live.data?.budgetMs != null && ` � dispatch budget ${live.data.budgetMs} ms`}. Actual attempts appear below.</p>
      <p>Autoscaling: Planned. CPU, memory and global throughput: unavailable.</p>
    </div>
  </Panel>;
}
export function WorkersTable() {
  const live = useLive<Capabilities>(getCapabilities);
  return <Panel title="Worker health and deployed models"><Availability {...live} />
    <p className="mb-3 text-xs text-muted-foreground">In-flight is this backend's reservation snapshot before its health probes, not cluster-wide load or a worker queue.</p>
    {live.data?.capabilities.length === 0 && <p>No worker pools configured.</p>}
    <div className="overflow-x-auto"><table className="w-full min-w-[700px] text-left text-sm">
      <thead><tr>{["Pool / policy", "Endpoint", "State", "Reported capability", "In-flight", "Model / revision"].map(h => <th className="pb-3 font-normal" key={h}>{h}</th>)}</tr></thead>
      <tbody>{live.data?.capabilities.flatMap(p => p.workers.map(w => <tr className="border-t hairline" key={p.capability + w.endpoint}>
        <td className="py-3">{p.capability}<div className="text-xs text-muted-foreground">{p.policy} � {p.healthyCount}/{p.workerCount} healthy</div></td>
        <td className="font-mono text-xs">{w.endpoint}</td>
        <td><Status tone={workerState(w, p.capability) === "Healthy" ? "ok" : "off"}>{workerState(w, p.capability)}</Status></td>
        <td>{w.reportedCapability ?? "Unknown"}</td><td>{w.inFlight ?? "Unavailable"}</td>
        <td className="max-w-xs break-all font-mono text-xs">{w.model ?? "Unavailable"}<div className="text-muted-foreground">{w.revision ?? "Revision unavailable"}</div></td>
      </tr>))}</tbody>
    </table></div>
  </Panel>;
}
export function ModelsScreen() {
  return <div className="space-y-4"><WorkersTable /><Panel title="Healthcare � chest X-ray">
    <p className="text-sm">Choose Healthcare in Inference, or Auto with the chest X-ray task. A dedicated HEALTHCARE worker is required. Research model scores are not a clinical diagnosis. General image classification is a separate task.</p>
  </Panel></div>;
}
function RequestEvidence() {
  const live = useLive<TrackedRequest[]>(getRequests);
  const rows = live.data;
  const counts = new Map<string, number>();
  rows?.forEach(r => { if (r.workerEndpoint) counts.set(r.workerEndpoint, (counts.get(r.workerEndpoint) ?? 0) + 1); });
  return <Panel title="Recorded requests and failover evidence"><Availability {...live} />
    <p className="mb-4 text-xs text-muted-foreground">Latest {rows?.length ?? "-"} stored requests (limit 100). Best-effort tracking can miss requests; these are sample counts, not global throughput. Endpoint totals use the recorded final endpoint, not every attempt.</p>
    {!!counts.size && <div className="mb-4 flex flex-wrap gap-4">{Array.from(counts).map(([endpoint, n]) => <p className="text-sm" key={endpoint}>{endpoint}: {n} recorded requests</p>)}</div>}
    {rows?.length === 0 && <p>No recorded requests yet.</p>}
    <div className="space-y-3">{rows?.map(r => <details className="rounded border hairline p-3 text-sm" key={r.requestId}>
      <summary className="cursor-pointer break-all">{r.status} � {r.capability} � {r.workerEndpoint ?? "No endpoint"} � {failoverState(r.routing)}</summary>
      <p className="mt-2 text-xs">{r.requestId} � {r.receivedAt} � {r.totalMs ?? "-"} ms {r.errorCode ? "� " + r.errorCode : ""}</p>
      {r.routing ? <RoutingDetails routing={r.routing} /> : <p className="mt-2">Routing evidence unavailable.</p>}
    </details>)}</div>
  </Panel>;
}
export function RoutingScreen() { return <div className="space-y-4"><OperationsSummary /><RequestEvidence /></div>; }
export function MonitoringScreen() { return <div className="space-y-4"><OperationsSummary /><WorkersTable /><RequestEvidence /></div>; }

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
  { id: "AUTO", label: "Auto", on: true },
  { id: "HEALTHCARE", label: "Healthcare", on: true },
  { id: "TEXT", label: "Text", on: true },
  { id: "CODE", label: "Code language", on: true },
  { id: "IMAGE", label: "Image", on: true },
] as const;

export function InferenceScreen() {
  const health = useHealth();
  const [capability, setCapability] = useState<Capability>("AUTO");
  const [text, setText] = useState("The new routing layer made our latency drop dramatically.");
  const [task, setTask] = useState<Task | "">("");
  const [uploading, setUploading] = useState(false);
  const medical = capability === "HEALTHCARE" || (capability === "AUTO" && task === "chest-xray");
  const [res, setRes] = useState<InferenceResult | null>(null);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true); setErr(null); setRes(null); setFailure(null);
    try { setRes(await api.inference(text, undefined, capability, capability === "AUTO" ? (task || undefined) : medical ? "chest-xray" : undefined)); } catch (e) { setErr((e as Error).message); if (e instanceof ApiError) setFailure(e); } finally { setBusy(false); }
  };
  const rt = res?.routing;
  const upload = async (file?: File) => {
    if (!file) return;
    setErr(null); setRes(null); setFailure(null); setText(""); setUploading(true);
    try {
      if (!["image/png", "image/jpeg"].includes(file.type)) throw new Error("Choose a PNG or JPEG image.");
      if (file.size > 5_000_000) throw new Error("Image must be no larger than 5 MB.");
      const data = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () => reject(new Error("Image could not be read."));
        reader.readAsDataURL(file);
      });
      setText(data);
    } catch (e) { setErr((e as Error).message); }
    finally { setUploading(false); }
  };
  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
      <Panel title={`Inference � ${capability}`} right={<span className="micro">POST {ENDPOINTS.inference}</span>}>
        <div className="mb-3 flex flex-wrap gap-2">
          {CAPS.map((c) => (
            <button key={c.id} type="button" disabled={busy || uploading} aria-pressed={capability === c.id}
              onClick={() => { setCapability(c.id); setText(""); setRes(null); setErr(null); setFailure(null); }}
              className={cn("rounded-[3px] border px-2 py-0.5 text-[12px]", capability === c.id ? "border-leaf/60 text-leaf" : "hairline text-muted-foreground")}>{c.label}</button>
          ))}
        </div>
        {capability === "AUTO" && <label className="mb-4 block text-sm">Task
          <select aria-label="Auto task" value={task} disabled={busy || uploading} onChange={e => { setTask(e.target.value as Task | ""); setRes(null); setErr(null); setFailure(null); }} className="ml-3 rounded border hairline bg-background p-2">
            <option value="">Detect text, code or image</option>
            <option value="sentiment-analysis">Text sentiment</option>
            <option value="code-language">Code language</option>
            <option value="image-classification">General image classification</option>
            <option value="chest-xray">Healthcare � chest X-ray</option>
          </select>
        </label>}
        {medical && <p className="mb-4 text-sm text-muted-foreground">Research demonstration only, not a diagnosis. Upload a frontal chest X-ray PNG or JPEG without personal identifiers. Model scores are not calibrated probabilities of disease; a clinician must interpret medical images.</p>}
        {["AUTO", "IMAGE", "HEALTHCARE"].includes(capability) && <label className="mb-4 block text-sm">
          {medical ? "Upload chest X-ray" : "Upload image"} (PNG/JPEG, up to 5 MB)
          <input className="mt-2 block w-full" aria-label="Upload image" type="file" accept="image/png,image/jpeg" disabled={busy || uploading}
            onChange={e => { void upload(e.target.files?.[0]); e.target.value = ""; }} />
        </label>}
        <textarea
          aria-label={medical || capability === "IMAGE" ? "PNG or JPEG data URL or base64" : capability === "CODE" ? "Code snippet" : capability === "AUTO" ? "Input for automatic routing" : "Text for sentiment analysis"}
          placeholder={medical || capability === "IMAGE" ? "Upload an image or paste a PNG/JPEG data URL" : capability === "CODE" ? "Paste a code snippet" : capability === "AUTO" ? "Enter text, code or upload an image; select a task if ambiguous" : "Enter text for sentiment analysis"}
          disabled={busy || uploading}
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={6}
          className="w-full resize-none rounded-[4px] border hairline bg-transparent p-3 text-[14px] outline-none focus:border-foreground/30"
        />
        <div className="mt-3 flex items-center justify-between gap-3">
          <Status tone={health.state === "ok" ? "ok" : health.state === "unset" ? "off" : "idle"}>
            {health.state === "ok" ? "Connected" : health.state === "checking" ? "Checking." : health.state === "down" ? "Unreachable" : "Not connected"}
          </Status>
          <button onClick={run} disabled={busy || uploading || !text.trim() || !isApiConfigured()} className="rounded-[3px] border border-leaf/60 px-4 py-1.5 text-[13px] text-leaf transition-colors hover:bg-leaf hover:text-primary-foreground disabled:opacity-40">
            {busy ? "Running." : "Run inference"}
          </button>
        </div>
        {!isApiConfigured() && <p className="mt-3 text-[12px] text-muted-foreground">Not connected - set VITE_API_BASE_URL to send real requests.</p>}
        {health.state === "down" && <p className="mt-3 font-mono text-[11px] text-destructive">{health.detail}</p>}
      </Panel>
      <Panel title="Response">
        {res ? (
          <div className="space-y-4">
            <div>
              <div className="num text-4xl">{res.prediction.label}</div>
              <div className="micro mt-2">score {res.prediction.score.toFixed(3)} � {res.totalLatencyMs}ms</div>
            </div>
            <dl className="grid grid-cols-[90px_1fr] gap-y-1 font-mono text-[11px]">
              {([["model", `${res.model} ${res.version}`], ["worker", res.worker], ["endpoint", res.workerEndpoint], ["request", res.requestId]] as const).map(([k, v]) => (
                <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="break-all">{v}</dd></div>
              ))}
            </dl>
            {res.capability && <p className="text-sm">Resolved capability: {res.capability}</p>}
            {medical && <p className="text-sm text-muted-foreground">Research score only - this result does not establish or exclude disease.</p>}
            {res.topK && <ul className="text-sm">{res.topK.map(p => <li key={p.label}>{p.label}: {p.score.toFixed(3)}</li>)}</ul>}
            {rt && <RoutingDetails routing={rt} />}

          </div>
        ) : err ? (
          <div><p className="font-mono text-[12px] text-destructive">{err}</p>{failure?.requestId && <p className="mt-2 text-xs">Request: {failure.requestId}</p>}{failure?.routing && <RoutingDetails routing={failure.routing} />}</div>
        ) : (
          <p className="text-[12px] text-muted-foreground">No request yet.</p>
        )}
      </Panel>
    </div>
  );
}


