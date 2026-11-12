import { createFileRoute } from "@tanstack/react-router";
import { PageTitle, Shell } from "@/components/nexus/Shell";
import { HomeScreen } from "@/components/nexus/screens";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Distributed Model Operations — NEXUS AI" },
      { name: "description", content: "Operate a distributed AI model serving cluster: deployments, workers and inference activity." },
      { property: "og:title", content: "Distributed Model Operations — NEXUS AI" },
      { property: "og:description", content: "Operate a distributed AI model serving cluster: deployments, workers and inference activity." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Page,
});

function Page() {
  return (
    <Shell>
      <PageTitle eyebrow="Cluster 01 · eu-west" title="Distributed model operations" />
      <HomeScreen />
    </Shell>
  );
}
