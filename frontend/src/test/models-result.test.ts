import { describe, expect, it } from "vitest";

import { toModelsResult } from "@/services/platform";

describe("toModelsResult", () => {
  it("treats a 200 JSON array as the real registry, even when empty", () => {
    expect(toModelsResult(200, [])).toEqual({ source: "registry", models: [] });
    const row = {
      id: "sentiment-distilbert",
      name: "DistilBERT",
      capability: "TEXT",
      task: "sentiment-analysis",
      version: "v1",
      workers: null,
      status: "REGISTERED",
    };
    expect(toModelsResult(200, [row])).toEqual({ source: "registry", models: [row] });
  });

  it("never presents an error response as registered models", () => {
    const failed = toModelsResult(500, {
      status: "error",
      error: "INTERNAL",
      message: "x",
      requestId: "r",
    });
    expect(failed.source).toBe("unavailable");
    if (failed.source === "unavailable") {
      expect(failed.reason).toContain("500");
      expect(failed.examples.length).toBeGreaterThan(0);
    }
    expect(toModelsResult(200, { not: "an array" }).source).toBe("unavailable");
    expect(toModelsResult(200, null).source).toBe("unavailable");
  });
});
