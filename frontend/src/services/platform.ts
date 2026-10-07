import type { Capability, Model, RoutingResult, Worker } from "@/types/platform";
import { modelCatalog } from "@/data/platform";
// Set VITE_API_BASE_URL once when the Spring Boot service is available.
const apiBase = import.meta.env["VITE_API_BASE_URL"]?.replace(/\/$/, "");
export const endpoints = {
  health: "/api/health",
  inference: "/api/inference",
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
export const platformService = {
  health: () => read(endpoints.health, { connected: false }),
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
    try {
      const body = new FormData();
      body.append("prompt", prompt);
      body.append("capability", capability);
      files.forEach((file) => body.append("files", file));
      const response = await fetch(`${apiBase}${endpoints.inference}`, {
        method: "POST",
        body,
        signal: AbortSignal.timeout(60000),
      });
      if (!response.ok)
        return {
          ...disconnectedResult,
          status: "failed",
          answer: "The request could not be completed. No routing decision is available.",
        };
      return (await response.json()) as RoutingResult;
    } catch {
      return { ...disconnectedResult };
    }
  },
};
