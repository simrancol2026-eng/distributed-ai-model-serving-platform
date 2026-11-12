import { createFileRoute } from "@tanstack/react-router";
import { PageTitle, Shell } from "@/components/nexus/Shell";
import { RoutingScreen } from "@/components/nexus/screens";

export const Route = createFileRoute("/routing")({
  head: () => ({
    meta: [
      { title: "Routing — NEXUS AI" },
      { name: "description", content: "Choose the load balancing algorithm used to route inference requests." },
      { property: "og:title", content: "Routing — NEXUS AI" },
      { property: "og:description", content: "Choose the load balancing algorithm used to route inference requests." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Page,
});

function Page() {
  return (
    <Shell>
      <PageTitle eyebrow="Load balancer" title="Routing strategy" />
      <RoutingScreen />
    </Shell>
  );
}
