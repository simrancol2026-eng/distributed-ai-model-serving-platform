# Nexus AI Operations dashboard

## Local production build (PowerShell)

From `frontend-operations`, with Node.js and the backend already configured:

```powershell
npm.cmd ci --legacy-peer-deps
$env:VITE_API_BASE_URL='http://localhost:8080'
npm.cmd run build
npm.cmd run test:production
npm.cmd run preview
```

Open `http://127.0.0.1:5173`. `npm start` uses the same production server.
`npm run preview -- --port 5174` changes the port (also update backend CORS).
`VITE_API_BASE_URL` is a build-time public origin, never a secret. Rebuild after
changing it. For hosting use the actual HTTPS origin, not localhost or `/api`.

Local builds default to Nitro `node-server`, emitting `.output/server/index.mjs`.
The preview/start scripts serve that final bundle; upstream `vite preview` can
look for the nonexistent intermediate `dist/server/server.js` and return 500.
`test:production` starts its own loopback server, checks five direct routes and
built assets, then stops it. It does not test browser hydration or inference.

The launcher defaults to loopback port 5173. Set `PORT`/`HOST` (or `NITRO_PORT`/
`NITRO_HOST`) for an authenticated HTTPS reverse proxy's private upstream.
This server provides no authentication or TLS by itself. See
[`SUBMISSION_READINESS.md`](../docs/SUBMISSION_READINESS.md) before deployment.

Managed Lovable builds retain the wrapper's pinned Cloudflare output. Explicit
`NITRO_PRESET` overrides target self-hosted builds; the Node launcher is only for
`node-server` output. A Cloudflare build must use its provider runtime, not this
Node launcher. Do not deploy stale output left by a different preset.

Verified locally: Node production build; TypeScript; 16 frontend tests; five
HTTP routes and eight static assets. Independent checks, hosted access controls,
browser/live API checks and actual two-machine evidence must be recorded for
the final submission version.

This project was built with [Lovable](https://lovable.dev).

## Build with Lovable

Open your project in the [Lovable editor](https://lovable.dev) and keep building.

- **Ship faster**: describe what you want to build and Lovable handles the code.
- **Stay in sync**: connect the project to GitHub and every change made in Lovable is committed straight to your repository.
- **Full ownership**: this code is yours. Push to your repository and your changes sync back into Lovable, ready for your next prompt.

## Development

Prefer working locally? You need Node.js and npm — [install with nvm](https://github.com/nvm-sh/nvm#installing-and-updating).

```sh
git clone <this-repository-url>
cd <repository-name>
npm i
npm run dev
```

## Built with

- TanStack Start
- TypeScript
- React
- Tailwind CSS

