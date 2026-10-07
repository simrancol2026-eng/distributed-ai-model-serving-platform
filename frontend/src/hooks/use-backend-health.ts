import { useQuery } from "@tanstack/react-query";
import { platformService } from "@/services/platform";

/** Polls GET /api/health every 10 s. `connected` is false until the first successful check. */
export function useBackendHealth() {
  const { data } = useQuery({
    queryKey: ["backend-health"],
    queryFn: platformService.health,
    refetchInterval: 10_000,
    retry: false,
  });
  return { connected: data?.connected ?? false };
}
