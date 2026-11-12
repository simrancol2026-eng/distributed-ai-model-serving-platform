import { createFileRoute } from "@tanstack/react-router";
import { PageTitle, Shell } from "@/components/nexus/Shell";
import { InferenceScreen } from "@/components/nexus/screens";

export const Route = createFileRoute("/inference")({
  head: () => ({
    meta: [
      { title: "Inference Console — NEXUS AI" },
      { name: "description", content: "Send text sentiment requests to the DistilBERT model through the gateway." },
      { property: "og:title", content: "Inference Console — NEXUS AI" },
      { property: "og:description", content: "Send text sentiment requests to the DistilBERT model through the gateway." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Page,
});

function Page() {
  return (
    <Shell>
      <PageTitle eyebrow="Console" title="Inference" />
      <InferenceScreen />
    </Shell>
  );
}
