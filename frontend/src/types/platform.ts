export type Capability = "AUTO" | "TEXT" | "CODE" | "IMAGE" | "DOCUMENT";
export type Model = {
  id: string;
  name: string;
  capability: Exclude<Capability, "AUTO">;
  task: string;
  version: string | null;
  workers: number | null;
  status: string;
  /** Present for registry entries (Sprint 2); absent in the static example catalog. */
  description?: string | null;
  createdAt?: string;
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
  /** Backend routing decision summary (Sprint 2 #5); absent when the backend sent none. */
  routing?: RoutingSummary | null;
};
/** Why the backend chose this worker (from InferenceResponse.routing). */
export type RoutingSummary = {
  strategy: string;
  eligibleCount: number;
  poolSize: number;
  chosen: string | null;
  reason: string;
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
