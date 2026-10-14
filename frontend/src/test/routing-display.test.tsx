import { act, fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { RoutingPanel } from "@/components/RoutingPanel";
import { useWorkspace, WorkspaceProvider } from "@/hooks/use-workspace";
import { platformService, routingBadgeLabel } from "@/services/platform";
import type { RoutingResult } from "@/types/platform";

// RoutingPanel only needs a link and the health flag; keep the router and the network out of this test.
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a href="#routing">{children}</a>,
}));
vi.mock("@/hooks/use-backend-health", () => ({ useBackendHealth: () => ({ connected: true }) }));

function result(eligibleCount: number, poolSize: number): RoutingResult {
  return {
    status: "completed",
    answer: "POSITIVE",
    model: "m",
    worker: "worker-a",
    latency: 5,
    origin: "backend",
    routing: {
      strategy: "ROUND_ROBIN_ALL",
      eligibleCount,
      poolSize,
      chosen: "127.0.0.1:8001",
      reason: "r",
    },
  };
}

function Submit() {
  const { submit } = useWorkspace();
  return <button onClick={() => void submit("hello", [])}>send</button>;
}

afterEach(() => vi.restoreAllMocks());

describe("routing details while a new request is pending", () => {
  it("does not show the previous decision as current, then shows the new one", async () => {
    let resolveSecond: (r: RoutingResult) => void = () => {};
    vi.spyOn(platformService, "infer")
      .mockResolvedValueOnce(result(1, 2))
      .mockImplementationOnce(() => new Promise((r) => (resolveSecond = r)));

    render(
      <WorkspaceProvider>
        <Submit />
        <RoutingPanel />
      </WorkspaceProvider>,
    );
    expect(screen.getByText("Backend reachable")).toBeInTheDocument();

    await act(async () => fireEvent.click(screen.getByText("send")));
    expect(screen.getByText("1 of 2")).toBeInTheDocument();

    await act(async () => fireEvent.click(screen.getByText("send"))); // second request, still in flight
    expect(screen.queryByText("1 of 2")).not.toBeInTheDocument();

    await act(async () => resolveSecond(result(2, 2)));
    expect(screen.getByText("2 of 2")).toBeInTheDocument();
  });
});

describe("routingBadgeLabel", () => {
  const base: RoutingResult = {
    status: "completed",
    answer: "",
    model: null,
    worker: null,
    latency: null,
  };
  it("names each state honestly", () => {
    expect(routingBadgeLabel(result(1, 2), false)).toBe("Backend decision");
    expect(routingBadgeLabel(result(1, 2), true)).toBe("Previous request");
    expect(routingBadgeLabel({ ...base, origin: "backend" }, false)).toBe("Decision unavailable");
    expect(routingBadgeLabel({ ...base, status: "failed", origin: "backend" }, false)).toBe(
      "Backend error response",
    );
    expect(routingBadgeLabel({ ...base, status: "failed", origin: "local" }, false)).toBe(
      "Not sent",
    );
    expect(routingBadgeLabel({ ...base, status: "not-connected" }, false)).toBe("Not connected");
  });
});
