import { createFileRoute } from "@tanstack/react-router";
import { PageTitle, Shell } from "@/components/nexus/Shell";
import { ModelsScreen } from "@/components/nexus/screens";

export const Route = createFileRoute("/models")({
  head: () => ({
    meta: [
      { title: "AI Models — NEXUS AI" },
      { name: "description", content: "Models served by the NEXUS AI platform and those planned." },
      { property: "og:title", content: "AI Models — NEXUS AI" },
      { property: "og:description", content: "Models served by the NEXUS AI platform and those planned." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Page,
});

function Page() {
  return (
    <Shell>
      <PageTitle eyebrow="Registry" title="AI models" />
      <ModelsScreen />
    </Shell>
  );
}
