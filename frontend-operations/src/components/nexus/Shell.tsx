import { Link } from "@tanstack/react-router";
import { useEffect, useState, type ReactNode } from "react";
import { API_BASE_URL } from "@/lib/api";
import { Status } from "./ui";

const NAV = [
  { to: "/", label: "Overview" },
  { to: "/models", label: "AI Models" },
  { to: "/inference", label: "Inference" },
  { to: "/routing", label: "Routing" },
  { to: "/monitoring", label: "Monitoring" },
] as const;

function Logo() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1" aria-hidden>
      <path d="M9 1.5 16 5.5v7L9 16.5 2 12.5v-7z" />
      <path d="M9 1.5v15M2 5.5l14 7M16 5.5l-14 7" strokeOpacity=".35" />
      <rect x="7.5" y="7.5" width="3" height="3" fill="var(--leaf)" stroke="none" />
    </svg>
  );
}

function Clock() {
  const [now, setNow] = useState<string>("");
  useEffect(() => {
    const f = () => setNow(new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }));
    f();
    const id = setInterval(f, 1000);
    return () => clearInterval(id);
  }, []);
  return <span className="micro tabular-nums">{now || "--:--:--"}</span>;
}

let introShown = false;

function Intro({ onDone }: { onDone: () => void }) {
  const word = "Nexus";
  const [out, setOut] = useState(false);
  useEffect(() => {
    const a = setTimeout(() => setOut(true), 2700);
    const b = setTimeout(onDone, 3600);
    return () => { clearTimeout(a); clearTimeout(b); };
  }, [onDone]);
  return (
    <div
      className={`fixed inset-0 z-50 flex items-center justify-center bg-outer transition-opacity duration-700 ${out ? "pointer-events-none opacity-0 delay-200" : ""}`}
      onClick={onDone}
      aria-label="Loading Nexus"
    >
      <div className="flex flex-col items-center">
        <h1 className={`text-6xl font-extralight tracking-[-0.02em] text-foreground md:text-[104px] ${out ? "intro-out" : ""}`}>
          {word.split("").map((c, i) => (
            <span key={i} className="letter" style={{ animationDelay: `${0.25 + i * 0.12}s` }}>{c}</span>
          ))}
        </h1>
        <div className="mt-8 h-px w-24 overflow-hidden bg-border">
          <div className="line-grow h-full w-full bg-foreground/50" />
        </div>
      </div>
    </div>
  );
}

export function Shell({ children }: { children: ReactNode }) {
  const [intro, setIntro] = useState(false);
  useEffect(() => {
    if (!introShown) { introShown = true; setIntro(true); }
  }, []);
  return (
    <div className="flex min-h-[100dvh] w-full flex-col bg-background">
      {intro && <Intro onDone={() => setIntro(false)} />}
      <div className="w-full flex-1 px-4 py-4 sm:px-6 md:px-10 md:py-6 xl:px-14">
        <header className="mb-6 border-b hairline pb-4 flex flex-wrap items-center justify-between gap-4">
          <Link to="/" className="flex items-center gap-2.5 text-foreground">
            <Logo />
            <span className="text-[12px] font-normal tracking-[0.22em]">NEXUS AI</span>
          </Link>
          <nav className="order-3 flex w-full gap-5 overflow-x-auto md:order-none md:w-auto">
            {NAV.map((n) => (
              <Link
                key={n.to}
                to={n.to}
                className="whitespace-nowrap text-[13px] text-muted-foreground transition-colors hover:text-foreground"
                activeProps={{ className: "!text-foreground" }}
                activeOptions={{ exact: true }}
              >
                {n.label}
              </Link>
            ))}
          </nav>
          <div className="flex items-center gap-4">
            <Status tone={API_BASE_URL ? "idle" : "off"}>{API_BASE_URL ? "API configured" : "API not connected"}</Status>
            <Clock />
          </div>
        </header>
        <main className="reveal">{children}</main>
      </div>
      <p className="micro border-t hairline px-4 py-3 text-center">NEXUS AI — Distributed Model Operations</p>
    </div>
  );
}

export function PageTitle({ eyebrow, title, right }: { eyebrow: string; title: string; right?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <div className="micro mb-2">{eyebrow}</div>
        <h1 className="num text-3xl text-foreground md:text-4xl">{title}</h1>
      </div>
      {right}
    </div>
  );
}
