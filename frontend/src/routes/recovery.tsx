import { createFileRoute } from "@tanstack/react-router";
import { RoutingPipeline } from "@/pages/RoutingPipeline";
import { pageHead } from "@/utils/metadata";
export const Route = createFileRoute("/recovery")({
  head: () =>
    pageHead(
      "Failure Recovery",
      "NEXUS AI distributed failure recovery and worker re-evaluation architecture.",
    ),
  component: () => <RoutingPipeline recovery />,
});
