import { useNavigate } from "@tanstack/react-router";
import { Bookmark, History, ArrowUpRight, Plus, MessageSquare } from "lucide-react";
import { useWorkspace } from "@/hooks/use-workspace";
import { PageHeader } from "@/components/PageHeader";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/StatusBadge";
export function RequestLibrary({ saved = false }: { saved?: boolean }) {
  const { entries, openRequest, newRequest, toggleSave } = useWorkspace();
  const navigate = useNavigate();
  const visible = [...entries].reverse().filter((e) => !saved || e.saved);
  return (
    <main className="platform-page">
      <PageHeader
        eyebrow="PERSONAL / WORKSPACE"
        title={saved ? "Saved Requests" : "Request History"}
        description={
          saved
            ? "Requests saved in your current workspace session."
            : "Your requests in the current workspace session."
        }
        action={
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              newRequest();
              navigate({ to: "/" });
            }}
          >
            <Plus />
            New request
          </Button>
        }
      />
      {visible.length ? (
        <div className="request-list">
          {visible.map((e) => (
            <div className="request-list-item" key={e.id}>
              <span className="request-list-icon">
                <MessageSquare size={18} />
              </span>
              <div className="request-list-main">
                <Button
                  variant="ghost"
                  onClick={() => {
                    openRequest(e.id);
                    navigate({ to: "/" });
                  }}
                >
                  {e.prompt}
                </Button>
                <div className="request-list-meta">
                  <span>{e.capability}</span>
                  <span>
                    {new Date(e.createdAt).toLocaleTimeString([], {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </span>
                  <span>
                    {e.result.status === "not-connected" ? "Not connected" : e.result.status}
                  </span>
                </div>
              </div>
              <Button
                variant="ghost"
                size="icon"
                title={e.saved ? "Unsave request" : "Save request"}
                aria-label={e.saved ? "Unsave request" : "Save request"}
                onClick={() => toggleSave(e.id)}
              >
                <Bookmark className={e.saved ? "fill-primary/20 text-primary" : ""} />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Open request"
                title="Open request"
                onClick={() => {
                  openRequest(e.id);
                  navigate({ to: "/" });
                }}
              >
                <ArrowUpRight />
              </Button>
            </div>
          ))}
        </div>
      ) : (
        <div className="empty-requests">
          {saved ? <Bookmark size={30} /> : <History size={30} />}
          <h2>{saved ? "No saved requests" : "No requests yet"}</h2>
          <p>
            {saved
              ? "Saved requests remain available during this session."
              : "Start a request in your AI workspace."}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              newRequest();
              navigate({ to: "/" });
            }}
          >
            <Plus />
            New request
          </Button>
        </div>
      )}
    </main>
  );
}
