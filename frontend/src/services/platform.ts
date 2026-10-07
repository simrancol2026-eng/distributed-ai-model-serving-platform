import type { Capability, Model, RoutingResult, Worker } from "@/types/platform";
import { modelCatalog } from "@/data/platform";
// Spring Boot origin, e.g. http://localhost:8080 (see frontend/.env.example).
const apiBase = import.meta.env["VITE_API_BASE_URL"]?.replace(/\/$/, "");
export const endpoints = {
  health: "/api/health",
  inference: "/api/inference",
  // Not implemented by the backend yet (later phases); the UI falls back to "Not connected".
  models: "/api/models",
  workers: "/api/workers",
  routing: "/api/routing",
  requests: "/api/requests",
  metrics: "/api/metrics",
} as const;
async function read<T>(endpoint: string, fallback: T): Promise<T> {
  if (!apiBase) return fallback;
  try {
    const response = await fetch(`${apiBase}${endpoint}`, { signal: AbortSignal.timeout(10000) });
    if (!response.ok) return fallback;
    return (await response.json()) as T;
  } catch {
    return fallback;
  }
}
export const disconnectedResult: RoutingResult = {
  status: "not-connected",
  answer:
    "Not connected — your request has not been sent to a model. Routing and inference require an available backend.",
  model: null,
  worker: null,
  latency: null,
};

/** Successful body of POST /api/inference (see backend InferenceDtos.InferenceResponse). */
export type InferenceSuccess = {
  status: "success";
  requestId: string;
  capability: string;
  worker: string;
  model: string;
  version: string | null;
  task: string;
  prediction: { label: string; score: number };
  workerInferenceMs: number;
  totalLatencyMs: number;
};
/** Error body of POST /api/inference (see backend InferenceDtos.ErrorResponse). */
export type InferenceError = { status: "error"; error: string; message: string; requestId: string };

const errorMessages: Record<string, string> = {
  WORKER_UNAVAILABLE: "The model worker is currently unavailable. Please try again shortly.",
  NO_ELIGIBLE_WORKER: "No model worker supports this capability yet.",
  INVALID_REQUEST: "The request was not valid.",
  WORKER_ERROR: "The model worker returned an error.",
};

/** Converts a backend response (HTTP status + parsed JSON) into what the workspace UI displays. */
export function toRoutingResult(httpStatus: number, body: unknown): RoutingResult {
  // Either shape may arrive, so read every field as optional.
  type AnyBody = Partial<Omit<InferenceSuccess, "status">> &
    Partial<Omit<InferenceError, "status">> & { status?: string };
  const b = body as AnyBody | null;
  if (httpStatus === 200 && b?.status === "success" && b.prediction) {
    const confidence = (b.prediction.score * 100).toFixed(1);
    return {
      status: "completed",
      answer: `${b.prediction.label} (confidence ${confidence}%) — ${b.task ?? "inference"}`,
      model: b.version ? `${b.model} @ ${b.version.slice(0, 8)}` : (b.model ?? null),
      worker: b.worker ?? null,
      latency: typeof b.totalLatencyMs === "number" ? b.totalLatencyMs : null,
    };
  }
  const code = b?.error ?? `HTTP_${httpStatus}`;
  const detail = b?.message ?? errorMessages[code] ?? "The request could not be completed.";
  return {
    ...disconnectedResult,
    status: "failed",
    answer: `Request failed (${code}): ${detail}`,
  };
}

export const platformService = {
  /** Backend liveness: any 200 from /api/health counts as connected (it returns plain text). */
  async health(): Promise<{ connected: boolean }> {
    if (!apiBase) return { connected: false };
    try {
      const response = await fetch(`${apiBase}${endpoints.health}`, {
        signal: AbortSignal.timeout(5000),
      });
      return { connected: response.ok };
    } catch {
      return { connected: false };
    }
  },
  models: () => read<Model[]>(endpoints.models, modelCatalog),
  workers: () => read<Worker[]>(endpoints.workers, []),
  routing: () => read(endpoints.routing, { selectedWorker: null, status: "Not connected" }),
  requests: () => read(endpoints.requests, []),
  metrics: () =>
    read(endpoints.metrics, {
      requests: null,
      latency: null,
      throughput: null,
      workers: null,
      failures: null,
    }),
  async infer(prompt: string, capability: Capability, files: File[]): Promise<RoutingResult> {
    if (!apiBase) return { ...disconnectedResult };
    if (files.length > 0) {
      // Phase 1 serves text only; file/image inference arrives with the image workers.
      return {
        ...disconnectedResult,
        status: "failed",
        answer: "File attachments are not supported yet — only text requests can be served.",
      };
    }
    try {
      const response = await fetch(`${apiBase}${endpoints.inference}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ capability, input: prompt }),
        signal: AbortSignal.timeout(60000),
      });
      let body: unknown = null;
      try {
        body = await response.json();
      } catch {
        body = null;
      }
      return toRoutingResult(response.status, body);
    } catch {
      return {
        ...disconnectedResult,
        answer: "Could not reach the backend. Check that the Spring Boot service is running.",
      };
    }
  },
};
