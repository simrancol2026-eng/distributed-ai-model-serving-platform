import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const port = process.env['SMOKE_PORT'] || '5197';
const base = `http://127.0.0.1:${port}`;
const child = spawn(process.execPath,
  [fileURLToPath(new URL('./serve-production.mjs', import.meta.url)),
    '--host', '127.0.0.1', '--port', port], { stdio: ['ignore', 'pipe', 'pipe'] });
let output = '';
child.stdout.on('data', data => { output += data; });
child.stderr.on('data', data => { output += data; });
let spawnError;
child.on('error', error => { spawnError = error; });
try {
  for (let i = 0; i < 100 && !output.includes('Listening on:'); i++) {
    if (spawnError) throw spawnError;
    if (child.exitCode !== null) throw new Error(`Server exited: ${output}`);
    await delay(100);
  }
  assert.match(output, /Listening on:/, `Server did not start: ${output}`);
  const assets = new Set();
  for (const route of ['/', '/inference', '/models', '/monitoring', '/routing']) {
    const response = await fetch(base + route, { signal: AbortSignal.timeout(10000) });
    assert.equal(response.status, 200, route);
    assert.match(response.headers.get('content-type') || '', /text\/html/);
    const html = await response.text();
    assert.match(html, /NEXUS/i, route);
    for (const match of html.matchAll(/(?:src|href)="(\/assets\/[^"?#]+)[^"]*"/g)) assets.add(match[1]);
    console.log(`PASS ${route}`);
  }
  assert.ok(assets.size > 0, 'Expected built asset links');
  for (const asset of assets) {
    const response = await fetch(base + asset, { signal: AbortSignal.timeout(10000) });
    assert.equal(response.status, 200, asset);
    assert.ok(!response.headers.get('content-type')?.includes('text/html'), asset);
  }
  console.log(`PASS ${assets.size} built assets; frontend HTTP smoke only, no live inference claim`);
} finally {
  if (child.exitCode === null && child.pid) {
    const exited = once(child, 'exit');
    child.kill();
    await exited;
  }
}
