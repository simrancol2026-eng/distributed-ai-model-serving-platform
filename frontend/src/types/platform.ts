export type Capability = "AUTO" | "TEXT" | "CODE" | "IMAGE" | "DOCUMENT";
export type Model = {
  id: string;
  name: string;
  capability: Exclude<Capability, "AUTO">;
  task: string;
  version: string | null;
  workers: number | null;
  status: string;
};
export type Worker = {
  id: string;
  model: string;
  capability: Exclude<Capability, "AUTO">;
  status: string;
  cpu: number | null;
  memory: number | null;
  requests: number | null;
  latency: number | null;
};
export type RoutingResult = {
  status: "not-connected" | "completed" | "failed";
  answer: string;
  model: string | null;
  worker: string | null;
  latency: number | null;
};
export type RequestEntry = {
  id: string;
  prompt: string;
  capability: Capability;
  files: string[];
  createdAt: number;
  result: RoutingResult;
  saved: boolean;
  feedback?: "positive" | "negative";
};
