import { ArrowUpRight } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { useWorkspace } from "@/hooks/use-workspace";
import { capabilities, routingFactors } from "@/data/platform";
import { StatusBadge } from "./StatusBadge";
import { Button } from "./ui/button";
export function RoutingPanel() {
  const { capability, entries, currentId } = useWorkspace();
  const entry = entries.find((e) => e.id === currentId);
  const selected = entry?.capability ?? capability;
  const task = capabilities.find((c) => c.id === selected)?.task ?? "Automatic detection";
  return (
    <div className="router-content geometric-router">
      <div className="router-connection">
        <div>
          <h2>Awaiting connection</h2>
          <p>Orchestration service</p>
        </div>
        <StatusBadge compact label="Offline" />
      </div>
      <div className="router-telemetry">
        <section className="router-detection">
          <div className="router-section-heading">
            <h3>Capability detection</h3>
            <span className="mono-tag">{selected}</span>
          </div>
          <p>{task}</p>
          <div className="router-confidence">
            <span>Confidence</span>
            <b>—</b>
          </div>
        </section>
        <section className="router-signals">
          <h3>Routing factors</h3>
          <div>
            {routingFactors.map((factor, i) => (
              <div className="router-signal" key={factor}>
                <span>0{i + 1}</span>
                <span>{factor}</span>
                <b>—</b>
              </div>
            ))}
          </div>
        </section>
        <dl className="router-assignment">
          <div>
            <dt>Eligible workers</dt>
            <dd>—</dd>
          </div>
          <div>
            <dt>Selected worker</dt>
            <dd>{entry?.result.worker ?? "—"}</dd>
          </div>
        </dl>
      </div>
      <div className="router-bottom">
        <div>
          <span className="tiny-dot" />
          <span>Not connected to backend</span>
        </div>
        <Button asChild variant="ghost" size="sm">
          <Link to="/routing">
            Routing pipeline <ArrowUpRight size={16} />
          </Link>
        </Button>
      </div>
    </div>
  );
}
