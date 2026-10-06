# NEXUS AI architecture
- Root layout owns the shared application shell and header; content lives in individual file-based TanStack routes with unique metadata so navigation and indexing stay consistent.
- UI colors, typography and shadows use semantic tokens in src/styles.css so themes remain coherent.
- Client API access goes exclusively through src/services/platform.ts and a single environment-configured API base so Spring Boot integration does not leak into presentation components.
- Backend-unavailable metrics and worker health show "—" or "Not connected"; named catalog models are intended configuration, not assertions of availability.
- Session-only conversation, saved requests and feedback live in the workspace provider; they are not server-persisted or authenticated.
- The geometric-core Three.js topology loads dynamically after hydration, reads semantic color tokens and disposes resources; ambient geometry never indicates measured traffic.
- Preserve essential build and error-reporting infrastructure while removing all visible third-party builder identity from the product.
