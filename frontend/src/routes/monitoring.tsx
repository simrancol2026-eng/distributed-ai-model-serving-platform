import { createFileRoute } from "@tanstack/react-router";
import { Monitoring, metricsQuery } from "@/pages/Monitoring";
import { pageHead } from "@/utils/metadata";
export const Route = createFileRoute("/monitoring")({
  head: () =>
    pageHead(
      "Platform Monitoring",
      "NEXUS AI platform health, throughput, latency and worker utilization.",
    ),
  loader: ({ context }) => context.queryClient.ensureQueryData(metricsQuery),
  component: Monitoring,
});
