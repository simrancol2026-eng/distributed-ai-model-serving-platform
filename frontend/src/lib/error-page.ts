export function renderErrorPage(): string {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <title>Workspace unavailable — NEXUS AI</title>
    <link rel="icon" href="/favicon.svg" type="image/svg+xml" />
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Epilogue:wght@400;500;600;700&display=swap" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <style>
      body { font: 15px/1.5 "Epilogue", system-ui, sans-serif; background: #0b0e0f; color: #dfe8e8; display: grid; place-items: center; min-height: 100vh; margin: 0; padding: 1.5rem; box-sizing: border-box; }
      .card { max-width: 28rem; width: 100%; text-align: center; padding: 2rem; border: 1px solid #263232; border-radius: 12px; background: #101617; }
      .brand { display: flex; justify-content: center; align-items: center; gap: 10px; color: #5bd4c8; font-size: 12px; letter-spacing: .12em; margin-bottom: 28px; }
      h1 { font-size: 1.25rem; margin: 0 0 0.5rem; }
      p { color: #8c9d9f; margin: 0 0 1.5rem; }
      .actions { display: flex; gap: 0.5rem; justify-content: center; flex-wrap: wrap; }
      a, button { padding: 0.5rem 1rem; border-radius: 0.375rem; font: inherit; cursor: pointer; text-decoration: none; border: 1px solid transparent; }
      .primary { background: #5bd4c8; color: #0b0e0f; }
      .secondary { background: #101617; color: #dfe8e8; border-color: #263232; }
    </style>
  </head>
  <body>
    <div class="card">
      <div class="brand"><img src="/favicon.svg" width="26" height="26" alt="" />NEXUS AI</div>
      <h1>Workspace unavailable</h1>
      <p>NEXUS could not load this workspace. Retry the connection or return to your AI workspace.</p>
      <div class="actions">
        <button class="primary" onclick="location.reload()">Try again</button>
        <a class="secondary" href="/">Open workspace</a>
      </div>
    </div>
  </body>
</html>`;
}
