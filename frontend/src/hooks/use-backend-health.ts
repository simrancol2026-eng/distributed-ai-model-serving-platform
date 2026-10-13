import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { platformService } from "@/services/platform";

/**
 * Polls GET /api/health every 10 s. `connected` is false until the first successful check.
 *
 * Hydration: the server always renders "not connected" (it never calls the backend).
 * Several components share this query, so its result can arrive in the cache
 * before a later component hydrates. That component's first render would then
 * say "connected" and no longer match the server HTML (React hydration error).
 * Each component therefore reports `false` until it has mounted, so its first
 * client render matches the server, and switches to the live value right after.
 */
export function useBackendHealth() {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const { data } = useQuery({
    queryKey: ["backend-health"],
    queryFn: platformService.health,
    refetchInterval: 10_000,
    retry: false,
  });
  return { connected: mounted && (data?.connected ?? false) };
}
