# NEXUS AI

Distributed AI Model Serving Platform.

## Application
- `/`: conversation workspace, custom WebGL topology and routing inspector
- `/history`, `/saved`: current-session requests
- `/models`, `/workers`: model catalog and worker discovery
- `/routing`, `/recovery`: orchestration and resilience architecture
- `/monitoring`: platform telemetry

The backend is not connected. Model names are intended catalog configuration, not claims of availability. No worker rows or telemetry values are fabricated. Animation visualizes topology, not live activity. Requests, saved state and feedback are held in memory and reset on refresh. Attachments are local until an API is configured.

## Spring Boot connection
Set `VITE_API_BASE_URL` to the service origin. Endpoint paths and all API communication are centralized in `src/services/platform.ts`. GET endpoints return the typed objects defined in `src/types/platform.ts`; inference accepts multipart `prompt`, `capability` and `files`, returning `RoutingResult`. Enable CORS for the deployed frontend origin. Add authentication and persistent request history as part of backend integration.
