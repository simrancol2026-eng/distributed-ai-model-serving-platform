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
  ) {
    super(message);
  }
}

export type Capability = "TEXT" | "CODE" | "IMAGE";
export interface RoutingEvidence {
  policy: string;
  chosen: string;
  reason: string;
  attempts?: unknown[] | number;
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
  routing?: RoutingEvidence;
}

/** Extract the server's error/message from a failed response body (JSON or text). */
export function errorFromBody(status: number, statusText: string, body: string): ApiError {
  let msg = body.trim();
  try {
    const j = JSON.parse(body) as { error?: unknown; message?: unknown };
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
  );
}

async function send(path: string, init?: RequestInit, base = API_BASE_URL): Promise<string> {
  if (!base) throw new ApiError("API not configured — set VITE_API_BASE_URL");
  const res = await fetch(base + path, init);
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
  /** Plain-text health; connected = any 2xx status. Returns the raw body. */
  health: (base?: string) =>
    send(ENDPOINTS.health, { headers: { Accept: "text/plain, */*" } }, base ?? API_BASE_URL),
  inference: async (input: string, base?: string, capability: Capability = "TEXT") =>
    parseInference(
      await send(
        ENDPOINTS.inference,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({ capability, input }),
        },
        base ?? API_BASE_URL,
      ),
    ),
};
