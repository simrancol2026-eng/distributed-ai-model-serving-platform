import { describe, expect, it } from "vitest";
import { toRoutingResult } from "@/services/platform";

// Exact shapes the Spring Boot /api/inference endpoint returns (InferenceDtos.java).
const success = {
  status: "success",
  requestId: "r-1",
  capability: "TEXT",
  worker: "model-a",
  model: "distilbert-base-uncased-finetuned-sst-2-english",
  version: "714eb0fa89d2f80546fda750413ed43d93601a13",
  task: "sentiment-analysis",
  prediction: { label: "POSITIVE", score: 0.9987 },
  workerInferenceMs: 15.2,
  totalLatencyMs: 42,
};

describe("toRoutingResult", () => {
  it("maps a successful backend response to a completed result", () => {
    const r = toRoutingResult(200, success);
    expect(r.status).toBe("completed");
    expect(r.answer).toContain("POSITIVE");
    expect(r.answer).toContain("99.9%");
    expect(r.worker).toBe("model-a");
    expect(r.model).toContain("distilbert");
    expect(r.model).toContain("714eb0fa");
    expect(r.latency).toBe(42);
  });

  it("carries the backend routing decision summary", () => {
    const r = toRoutingResult(200, {
      ...success,
      routing: {
        strategy: "VERSION_AWARE",
        target: "art@714eb0fa",
        poolSize: 3,
        eligibleCount: 1,
        candidates: [],
        chosen: "127.0.0.1:8001",
        reason: "model 'sent' version 'v1': round-robin over 1 eligible of 3 worker(s)",
      },
    });
    expect(r.routing).toEqual({
      strategy: "VERSION_AWARE",
      eligibleCount: 1,
      poolSize: 3,
      chosen: "127.0.0.1:8001",
      reason: "model 'sent' version 'v1': round-robin over 1 eligible of 3 worker(s)",
    });
  });

  it("has no routing summary when the backend sends none", () => {
    expect(toRoutingResult(200, success).routing).toBeNull();
  });

  it("shows the model name without a version when the worker reports none", () => {
    expect(toRoutingResult(200, { ...success, version: null }).model).toBe(success.model);
  });

  it("maps a worker-unavailable error to a failed result with the backend message", () => {
    const r = toRoutingResult(503, {
      status: "error",
      error: "WORKER_UNAVAILABLE",
      message: "The model worker is not reachable. Please try again shortly.",
      requestId: "r-2",
    });
    expect(r.status).toBe("failed");
    expect(r.answer).toContain("WORKER_UNAVAILABLE");
    expect(r.answer).toContain("not reachable");
    expect(r.model).toBeNull();
    expect(r.worker).toBeNull();
  });

  it("maps an unsupported capability to a failed result", () => {
    const r = toRoutingResult(422, {
      status: "error",
      error: "NO_ELIGIBLE_WORKER",
      message: "No worker is registered for capability IMAGE. Available: TEXT.",
      requestId: "r-3",
    });
    expect(r.status).toBe("failed");
    expect(r.answer).toContain("IMAGE");
  });

  it("never fakes a result when the body is unreadable", () => {
    const r = toRoutingResult(500, null);
    expect(r.status).toBe("failed");
    expect(r.answer).toContain("HTTP_500");
    expect(r.model).toBeNull();
  });
});
