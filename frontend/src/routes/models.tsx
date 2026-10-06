import { createFileRoute } from "@tanstack/react-router";
import { ModelRegistry, modelsQuery } from "@/pages/ModelRegistry";
import { pageHead } from "@/utils/metadata";
export const Route = createFileRoute("/models")({
  head: () =>
    pageHead(
      "Model Registry",
      "Explore the NEXUS AI specialized model catalog and connected registry status.",
    ),
  loader: ({ context }) => context.queryClient.ensureQueryData(modelsQuery),
  component: ModelRegistry,
});
