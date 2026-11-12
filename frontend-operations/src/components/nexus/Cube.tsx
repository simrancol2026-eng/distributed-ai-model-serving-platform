import { useEffect, useState } from "react";

type V = [number, number, number];
const TILT = 0.52;

function project([x, y, z]: V, a: number): [number, number, number] {
  const xr = x * Math.cos(a) - z * Math.sin(a);
  const zr = x * Math.sin(a) + z * Math.cos(a);
  return [xr, -y * Math.cos(TILT) + zr * Math.sin(TILT), zr];
}

const cubeEdges = (c: V, s: V): [V, V][] => {
  const [cx, cy, cz] = c;
  const [sx, sy, sz] = s.map((v) => v / 2) as V;
  const p = (i: number, j: number, k: number): V => [cx + i * sx, cy + j * sy, cz + k * sz];
  const e: [V, V][] = [];
  for (const j of [-1, 1]) for (const k of [-1, 1]) e.push([p(-1, j, k), p(1, j, k)]);
  for (const i of [-1, 1]) for (const k of [-1, 1]) e.push([p(i, -1, k), p(i, 1, k)]);
  for (const i of [-1, 1]) for (const j of [-1, 1]) e.push([p(i, j, -1), p(i, j, 1)]);
  return e;
};

// 3 floors × 3×3 rack of worker blocks
const blocks: { c: V; active: boolean; seed: number }[] = [];
for (let y = 0; y < 3; y++)
  for (let x = -1; x <= 1; x++)
    for (let z = -1; z <= 1; z++)
      blocks.push({ c: [x * 46, -38 + y * 38, z * 46], active: (x + z + y * 2 + 9) % 3 !== 0, seed: x * 7 + z * 3 + y });

export function InfraCube({ size = 520 }: { size?: number }) {
  const [t, setT] = useState(0);
  useEffect(() => {
    let raf = 0;
    const start = performance.now();
    const loop = (n: number) => {
      setT((n - start) / 1000);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, []);

  const a = 0.785 + t * 0.12;
  const P = (v: V) => project(v, a);
  const line = ([p, q]: [V, V]) => {
    const A = P(p), B = P(q);
    return { x1: A[0], y1: A[1], x2: B[0], y2: B[1] };
  };

  const shell = cubeEdges([0, 0, 0], [200, 160, 200]).map(line);
  const outer = cubeEdges([0, 0, 0], [250, 210, 250]).map(line);
  const floors = [-57, -19, 19, 57].map((y) => {
    const c: V[] = [[-100, y, -100], [100, y, -100], [100, y, 100], [-100, y, 100]];
    return c.map(P).map((p) => `${p[0]},${p[1]}`).join(" ");
  });
  const grid: ReturnType<typeof line>[] = [];
  for (let i = -5; i <= 5; i++) {
    grid.push(line([[i * 40, -140, -200], [i * 40, -140, 200]]));
    grid.push(line([[-200, -140, i * 40], [200, -140, i * 40]]));
  }

  const sorted = [...blocks].sort((b1, b2) => P(b1.c)[2] - P(b2.c)[2]).reverse();
  const pulse = (edge: [V, V], phase: number) => {
    const k = (t * 0.4 + phase) % 1;
    const v: V = [0, 1, 2].map((i) => edge[0][i]! + (edge[1][i]! - edge[0][i]!) * k) as V;
    return P(v);
  };
  const outerRaw = cubeEdges([0, 0, 0], [250, 210, 250]);

  return (
    <svg viewBox="-260 -230 520 460" width="100%" style={{ maxWidth: size }} className="overflow-visible" role="img" aria-label="Animated wireframe of the model serving cluster">
      <defs>
        <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="1.6" result="b" />
          <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
        </filter>
      </defs>
      <g stroke="var(--foreground)" strokeOpacity="0.05" strokeWidth="0.6">
        {grid.map((l, i) => <line key={i} {...l} />)}
      </g>
      <g stroke="var(--foreground)" strokeOpacity="0.22" strokeWidth="0.6" strokeDasharray="1.5 4">
        {outer.map((l, i) => <line key={i} {...l} />)}
      </g>
      <g fill="none" stroke="var(--foreground)" strokeOpacity="0.09" strokeWidth="0.6">
        {floors.map((f, i) => <polygon key={i} points={f} />)}
      </g>
      {sorted.map((b, i) => {
        const on = b.active && Math.sin(t * 1.3 + b.seed) > -0.6;
        const edges = cubeEdges(b.c, [26, 22, 26]).map(line);
        const top: V[] = [[-13, 11, -13], [13, 11, -13], [13, 11, 13], [-13, 11, 13]].map(
          (o) => [b.c[0] + o[0]!, b.c[1] + o[1]!, b.c[2] + o[2]!] as V,
        );
        return (
          <g key={i} filter={on ? "url(#glow)" : undefined}>
            <polygon
              points={top.map(P).map((p) => `${p[0]},${p[1]}`).join(" ")}
              fill={on ? "var(--leaf)" : "var(--foreground)"}
              fillOpacity={on ? 0.32 : 0.04}
            />
            {edges.map((l, j) => (
              <line key={j} {...l} stroke={on ? "var(--leaf)" : "var(--foreground)"} strokeOpacity={on ? 0.8 : 0.28} strokeWidth="0.7" />
            ))}
          </g>
        );
      })}
      <g stroke="var(--foreground)" strokeOpacity="0.5" strokeWidth="0.7">
        {shell.map((l, i) => <line key={i} {...l} />)}
      </g>
      {outerRaw.map((e, i) => {
        const [x, y] = pulse(e, i * 0.137);
        return <circle key={i} cx={x} cy={y} r="1.4" fill="var(--leaf)" opacity="0.75" />;
      })}
    </svg>
  );
}
