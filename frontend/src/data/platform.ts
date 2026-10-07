import {
  MessageSquare,
  Code2,
  ScanLine,
  FileText,
  Layers,
  Server,
  Network,
  Activity,
  ShieldCheck,
} from "lucide-react";
import type { Model } from "@/types/platform";
export const capabilities = [
  {
    id: "TEXT",
    title: "Text",
    icon: MessageSquare,
    description: "Ask questions, summarize, analyze",
    tone: "text-primary",
    task: "Text Generation",
  },
  {
    id: "CODE",
    title: "Code",
    icon: Code2,
    description: "Generate, debug, explain",
    tone: "text-code",
    task: "Code Generation",
  },
  {
    id: "IMAGE",
    title: "Image",
    icon: ScanLine,
    description: "Analyze and classify",
    tone: "text-image",
    task: "Image Classification",
  },
  {
    id: "DOCUMENT",
    title: "Document",
    icon: FileText,
    description: "Analyze documents",
    tone: "text-accent",
    task: "Document Analysis",
  },
] as const;
export const platformNav = [
  { to: "/models", label: "Models", icon: Layers },
  { to: "/workers", label: "Workers", icon: Server },
  { to: "/routing", label: "Routing", icon: Network },
  { to: "/monitoring", label: "Monitoring", icon: Activity },
  { to: "/recovery", label: "Failure recovery", icon: ShieldCheck },
] as const;
// Intended model catalog, not a claim of registered or available services.
export const modelCatalog: Model[] = [
  {
    id: "resnet50",
    name: "ResNet50",
    capability: "IMAGE",
    task: "Image Classification",
    version: null,
    workers: null,
    status: "Not connected",
  },
  {
    id: "alexnet",
    name: "AlexNet",
    capability: "IMAGE",
    task: "Image Classification",
    version: null,
    workers: null,
    status: "Not connected",
  },
  {
    id: "cnn",
    name: "CNN",
    capability: "IMAGE",
    task: "Image Classification",
    version: null,
    workers: null,
    status: "Not connected",
  },
  {
    id: "qwen",
    name: "Qwen Text Model",
    capability: "TEXT",
    task: "Text Generation",
    version: null,
    workers: null,
    status: "Not connected",
  },
  {
    id: "coding",
    name: "Coding Model",
    capability: "CODE",
    task: "Code Generation",
    version: null,
    workers: null,
    status: "Not connected",
  },
];
export const routingFactors = [
  "Capability Match",
  "Worker Health",
  "Current Workload",
  "Latency",
  "Resource Availability",
  "Reliability",
];
