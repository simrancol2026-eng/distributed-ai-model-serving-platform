import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";

const B = "http://x";
afterEach(() => vi.unstubAllGlobals());

describe("api client", () => {
  it("treats plain-text 200 health as connected", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("OK", { status: 200 })),
    );
    await expect(api.health(B)).resolves.toBe("OK");
  });
  it("posts TEXT capability to /api/inference", async () => {
    const f = vi.fn(
      async () =>
        new Response(JSON.stringify({ prediction: { label: "POSITIVE", score: 0.9 } }), {
          status: 200,
        }),
    );
    vi.stubGlobal("fetch", f);
    await api.inference("hi", B);
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://x/api/inference");
    expect(JSON.parse(init.body as string)).toEqual({ capability: "TEXT", input: "hi" });
  });
  it("surfaces server error message instead of succeeding", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ error: "Bad Request", message: "input blank" }), {
            status: 400,
          }),
      ),
    );
    await expect(api.inference("", B)).rejects.toThrow("input blank");
  });
  it.each(["CODE", "IMAGE"] as const)("posts the selected %s capability", async (capability) => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      expect(JSON.parse(String(init.body))).toEqual({ capability, input: "sample" });
      return new Response(JSON.stringify({ prediction: { label: "example", score: 0.9 } }));
    });
    vi.stubGlobal("fetch", fetchMock);
    await api.inference("sample", B, capability);
    expect(fetchMock).toHaveBeenCalledOnce();
  });
  it("refuses without a base URL", async () => {
    await expect(api.health("")).rejects.toThrow("not configured");
  });
});
