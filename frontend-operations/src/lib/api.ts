/**
 * NEXUS AI API client — Spring Boot serving platform, contract verified at baseline 7dc4649.
 * Base URL comes from VITE_API_BASE_URL; when unset no request is made and no URL is assumed.
 * TEXT, CODE and IMAGE share the inference endpoint.
 */
export const API_BASE_URL: string | undefined =
  (import.meta.env["VITE_API_BASE_URL"] as string | undefined)?.replace(/\/$/, "") || undefined;

export const isApiConfigured = () => Boolean(API_BASE_URL);

export const ENDPOINTS = { health: "/api/health", inference: "/api/inference" } as const;

export class ApiError extends Error {
  constructor(
    message: string,
    public status?: number,
    public requestId?: string,
    public routing?: RoutingEvidence,
  ) {
    super(message);
  }
}

export type Capability = "AUTO" | "TEXT" | "CODE" | "IMAGE" | "HEALTHCARE";
export type Task = "sentiment-analysis" | "code-language" | "image-classification" | "chest-xray";
export interface WorkerView { endpoint: string; reachable: boolean; healthy: boolean; reportedCapability: string | null; model: string | null; revision: string | null; inFlight?: number }
export interface Pool { capability: string; policy: string; workerCount: number; healthyCount: number; workers: WorkerView[] }
export interface Capabilities { defaultCapability: string; capabilities: Pool[]; failoverEnabled?: boolean; budgetMs?: number }
export interface Attempt { endpoint: string; outcome: string; elapsedMs: number; failureClass: string | null }
export interface TrackedRequest { requestId: string; status: string; capability: string; workerEndpoint: string | null; totalMs: number | null; receivedAt: string; errorCode: string | null; routing?: RoutingEvidence | null }
export interface RoutingEvidence {
  policy: string;
  chosen: string;
  reason: string;
  attempts?: Attempt[];
  poolSize?: number;
  eligibleCount?: number;
  candidates?: { endpoint: string; eligible: boolean; note: string; inFlight: number | null }[];
  terminalReason?: string;
}
export interface InferenceResult {
  prediction: { label: string; score: number };
  worker: string;
  workerEndpoint: string;
  model: string;
  version: string;
  totalLatencyMs: number;
  requestId: string;
  capability?: string;
  task?: string;
  topK?: { label: string; score: number }[];
  routing?: RoutingEvidence;
}

/** Extract the server's error/message from a failed response body (JSON or text). */
export function errorFromBody(status: number, statusText: string, body: string): ApiError {
  let msg = body.trim();
  let requestId: string | undefined;
  let routing: RoutingEvidence | undefined;
  try {
    const j = JSON.parse(body) as { error?: unknown; message?: unknown; requestId?: string; routing?: RoutingEvidence };
    requestId = j.requestId;
    routing = j.routing;
    const parts = [j.error, j.message].filter(
      (x): x is string => typeof x === "string" && x.length > 0,
    );
    if (parts.length) msg = parts.join(": ");
  } catch {
    /* plain text body */
  }
  return new ApiError(
    `${status}${statusText ? " " + statusText : ""}${msg ? " — " + msg : ""}`,
    status,
    requestId,
    routing,
  );
}

async function send(path: string, init?: RequestInit, base = API_BASE_URL): Promise<string> {
  if (!base) throw new ApiError("API not configured — set VITE_API_BASE_URL");
  const res = await fetch(base + path, { ...init, signal: init?.signal ?? AbortSignal.timeout(path === ENDPOINTS.inference ? 60000 : 15000) });
  const text = await res.text();
  if (!res.ok) throw errorFromBody(res.status, res.statusText, text);
  return text;
}

export function parseInference(text: string): InferenceResult {
  const r = JSON.parse(text) as InferenceResult;
  if (!r || typeof r.prediction?.label !== "string" || typeof r.prediction?.score !== "number")
    throw new ApiError("Malformed inference response — missing prediction.label/score");
  return r;
}

export const api = {
  capabilities: async (base?: string): Promise<Capabilities> => {
    const value = JSON.parse(await send("/api/capabilities", undefined, base ?? API_BASE_URL));
    if (!Array.isArray(value?.capabilities) || value.capabilities.some((p: Pool) =>
      typeof p.capability !== "string" || typeof p.policy !== "string" || !Number.isInteger(p.workerCount) || !Number.isInteger(p.healthyCount) || !Array.isArray(p.workers) ||
      p.workers.some(w => typeof w.endpoint !== "string" || typeof w.reachable !== "boolean" || typeof w.healthy !== "boolean")))
      throw new ApiError("Malformed worker health response");
    return value;
  },
  requests: async (base?: string): Promise<TrackedRequest[]> => {
    const value = JSON.parse(await send("/api/requests?limit=100", undefined, base ?? API_BASE_URL));
    if (!Array.isArray(value) || value.some(r => typeof r.requestId !== "string" || typeof r.status !== "string")) throw new ApiError("Malformed request history response");
    return value;
  },
  /** Plain-text health; connected = any 2xx status. Returns the raw body. */
  health: (base?: string) =>
    send(ENDPOINTS.health, { headers: { Accept: "text/plain, */*" } }, base ?? API_BASE_URL),
  inference: async (input: string, base?: string, capability: Capability = "TEXT", task?: Task) =>
    parseInference(
      await send(
        ENDPOINTS.inference,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({ capability, input, ...(task ? { task } : {}) }),
        },
        base ?? API_BASE_URL,
      ),
    ),
};

