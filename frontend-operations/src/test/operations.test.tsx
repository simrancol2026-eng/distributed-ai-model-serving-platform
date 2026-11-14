import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { HomeScreen, InferenceScreen, workerState, failoverState } from "@/components/nexus/screens";
import { api, errorFromBody, type WorkerView, type RoutingEvidence } from "@/lib/api";

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
const worker: WorkerView = { endpoint: "host:8001", reachable: true, healthy: true, reportedCapability: "TEXT", model: "sentiment", revision: "rev" };
const routing: RoutingEvidence = { policy: "ROUND_ROBIN", chosen: "host:8002", reason: "alternate", attempts: [
  { endpoint: "host:8001", outcome: "FAILED", elapsedMs: 2, failureClass: "CONNECT_FAILURE" },
  { endpoint: "host:8002", outcome: "SUCCEEDED", elapsedMs: 5, failureClass: null },
] };

describe("operational evidence", () => {
  it("distinguishes failure, mismatch and health", () => {
    expect(workerState(worker, "TEXT")).toBe("Healthy");
    expect(workerState({ ...worker, reachable: false }, "TEXT")).toBe("Unreachable");
    expect(workerState({ ...worker, healthy: false }, "TEXT")).toBe("Unhealthy");
    expect(workerState(worker, "CODE")).toBe("Capability mismatch");
  });
  it("only claims recovery for a successful distinct alternate", () => {
    expect(failoverState(routing)).toBe("Recovered on another worker");
    expect(failoverState({ ...routing, attempts: routing.attempts!.slice(0, 1) })).toBe("No retry recorded");
    expect(failoverState({ ...routing, attempts: routing.attempts!.map(a => ({ ...a, endpoint: "same" })) })).toBe("No alternate worker recorded");
    expect(failoverState(null)).toBe("Attempt evidence unavailable");
  });
  it("preserves failed request routing and ID", () => {
    const error = errorFromBody(503, "Unavailable", JSON.stringify({ error: "WORKER_UNAVAILABLE", requestId: "req", routing }));
    expect(error.routing).toEqual(routing);
    expect(error.requestId).toBe("req");
  });
  it("removes weekly activity and invented metrics", () => {
    const html = renderToStaticMarkup(<HomeScreen />);
    expect(html).not.toMatch(/activity.*week/i);
    expect(html).not.toContain("1,284");
    expect(html).not.toContain("Demo data");
  });
  it("offers explicit healthcare upload and Auto task selection", () => {
    const html = renderToStaticMarkup(<InferenceScreen />);
    expect(html).toContain('aria-label="Auto task"');
    expect(html).toContain('aria-label="Upload image"');
    expect(html).toContain('value="chest-xray"');
    expect(html).toContain('Healthcare');
  });
  it("posts AUTO task instead of silently sending TEXT", async () => {
    const fetchMock = vi.fn(async (_url, init) => {
      expect(JSON.parse(init.body)).toEqual({ capability: "AUTO", task: "chest-xray", input: "data" });
      return new Response(JSON.stringify({ prediction: { label: "research score", score: 0.1 } }));
    });
    vi.stubGlobal("fetch", fetchMock);
    await api.inference("data", "http://backend", "AUTO", "chest-xray");
  });
  it("rejects malformed telemetry instead of reporting zero healthy workers", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ capabilities: [{}] }))));
    await expect(api.capabilities("http://backend")).rejects.toThrow("Malformed");
  });
});

