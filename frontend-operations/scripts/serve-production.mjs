import { existsSync } from 'node:fs';

// Serve Nitro's actual output instead of TanStack's intermediate Vite SSR entry.
// Bind locally by default; expose only behind an authenticated HTTPS gateway.
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i += 2) {
  const flag = args[i];
  const value = args[i + 1];
  if (!['--port', '--host'].includes(flag) || !value || value.startsWith('--')) {
    throw new Error('Usage: npm run preview -- [--port 5173] [--host 127.0.0.1]');
  }
  process.env[flag === '--port' ? 'NITRO_PORT' : 'NITRO_HOST'] = value;
}
const port = process.env.NITRO_PORT || process.env.PORT || '5173';
if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
  throw new Error('PORT must be an integer from 1 to 65535');
}
process.env.NITRO_PORT = port;
process.env.NITRO_HOST ||= process.env.HOST || '127.0.0.1';
const entry = new URL('../.output/server/index.mjs', import.meta.url);
if (!existsSync(entry)) {
  throw new Error('Node production output missing. Run npm run build with NITRO_PRESET=node-server first.');
}
await import(entry.href);
