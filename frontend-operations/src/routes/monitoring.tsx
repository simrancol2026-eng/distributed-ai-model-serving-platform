import { createFileRoute } from "@tanstack/react-router";
import { PageTitle, Shell } from "@/components/nexus/Shell";
import { MonitoringScreen } from "@/components/nexus/screens";

export const Route = createFileRoute("/monitoring")({
  head: () => ({
    meta: [
      { title: "Monitoring — NEXUS AI" },
      { name: "description", content: "Worker health, resource usage and gateway logs." },
      { property: "og:title", content: "Monitoring — NEXUS AI" },
      { property: "og:description", content: "Worker health, resource usage and gateway logs." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Page,
});

function Page() {
  return (
    <Shell>
      <PageTitle eyebrow="Observability" title="Monitoring" />
      <MonitoringScreen />
    </Shell>
  );
}
