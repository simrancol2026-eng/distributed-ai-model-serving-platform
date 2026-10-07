import { createFileRoute } from "@tanstack/react-router";
import { AIWorkspace } from "@/components/AIWorkspace";
import { pageHead } from "@/utils/metadata";
export const Route = createFileRoute("/")({
  head: () =>
    pageHead(
      "Intelligent Workspace",
      "NEXUS AI — Distributed AI Model Serving Platform. One workspace for text, code, images and documents.",
    ),
  component: AIWorkspace,
});
