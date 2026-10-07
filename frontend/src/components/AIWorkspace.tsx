import { useState, useRef, useCallback, useEffect } from "react";
import {
  ArrowUp,
  Paperclip,
  ChevronDown,
  Network,
  X,
  Copy,
  RotateCcw,
  Bookmark,
  ThumbsUp,
  ThumbsDown,
  Check,
  FileText,
  ArrowRight,
  LoaderCircle,
} from "lucide-react";
import { Button } from "./ui/button";
import { NetworkVisualization } from "./NetworkVisualization";
import { NexusLogo } from "./NexusLogo";
import { capabilities } from "@/data/platform";
import { useWorkspace } from "@/hooks/use-workspace";
import { useBackendHealth } from "@/hooks/use-backend-health";
import type { Capability } from "@/types/platform";
import { StatusBadge } from "./StatusBadge";
export function AIWorkspace() {
  const { capability, setCapability, entries, currentId, pending, submit, toggleSave, feedback } =
    useWorkspace();
  const backend = useBackendHealth();
  const [prompt, setPrompt] = useState(""),
    [files, setFiles] = useState<File[]>([]),
    [error, setError] = useState(""),
    [copied, setCopied] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const conversation = useRef<HTMLDivElement>(null);
  const current = entries.find((e) => e.id === currentId);
  const select = useCallback((value: Capability) => setCapability(value), [setCapability]);
  useEffect(() => {
    conversation.current?.scrollTo({ top: conversation.current.scrollHeight, behavior: "smooth" });
  }, [currentId, pending]);
  async function send() {
    if (!prompt.trim() && !files.length) return;
    await submit(prompt, files);
    setPrompt("");
    setFiles([]);
  }
  async function copy() {
    if (!current) return;
    try {
      await navigator.clipboard.writeText(current.result.answer);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      setError("Copy is unavailable in this browser.");
    }
  }
  function attach(list: FileList | null) {
    if (!list) return;
    const accepted = Array.from(list);
    if (accepted.some((f) => f.size > 20 * 1024 * 1024)) {
      setError("Each attachment must be 20 MB or smaller.");
      return;
    }
    setError("");
    setFiles((previous) => [...previous, ...accepted]);
  }
  return (
    <main className="workspace-page">
      <div className={`workspace-scroll ${current ? "conversation-mode" : ""}`} ref={conversation}>
        {!current ? (
          <div className="workspace-welcome">
            <NetworkVisualization active={capability} onSelect={select} />
            <div className="workspace-greeting">
              <h2>Your AI workspace, intelligently routed.</h2>
              <p>
                Ask, create, analyze, or build. NEXUS AI selects an appropriate AI service
                <br className="wide-break" /> based on capability and current worker conditions.
              </p>
            </div>
            <div className="capability-cards">
              {capabilities.map((c) => (
                <Button
                  key={c.id}
                  variant="ghost"
                  aria-pressed={capability === c.id}
                  className={`capability-card ${capability === c.id ? "selected" : ""}`}
                  onClick={() => setCapability(c.id)}
                >
                  <span className={`capability-card-icon ${c.tone}`}>
                    <c.icon size={19} />
                  </span>
                  <span className="capability-card-name">
                    {c.id}
                    <ArrowRight size={12} />
                  </span>
                  <span className="capability-card-description">{c.description}</span>
                </Button>
              ))}
            </div>
          </div>
        ) : (
          <div className="conversation">
            <div className="conversation-user">
              <span className="message-avatar">YOU</span>
              <div>
                <p className="message-label">
                  You{" "}
                  <span>
                    {new Date(current.createdAt).toLocaleTimeString([], {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </span>
                </p>
                <p className="message-body">{current.prompt}</p>
                {current.files.map((file, i) => (
                  <span className="attachment-tag" key={`${file}-${i}`}>
                    <FileText size={13} />
                    {file}
                  </span>
                ))}
              </div>
            </div>
            <div className="conversation-assistant">
              <span className="assistant-avatar">
                <NexusLogo size={24} />
              </span>
              <div className="assistant-body">
                <p className="message-label">
                  NEXUS AI <span>AUTO ROUTER</span>
                </p>
                <p className="message-body" role="status">
                  {current.result.answer}
                </p>
                <div className="response-routing">
                  <div className="response-routing-title">
                    <Network size={14} />
                    Routing details
                    <StatusBadge />
                  </div>
                  <dl>
                    {[
                      ["Model", current.result.model],
                      ["Worker", current.result.worker],
                      [
                        "Latency",
                        current.result.latency === null ? null : `${current.result.latency} ms`,
                      ],
                      [
                        "Status",
                        current.result.status === "not-connected" ? null : current.result.status,
                      ],
                    ].map(([label, value]) => (
                      <div key={label}>
                        <dt>{label}</dt>
                        <dd>{value ?? "—"}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
                <div className="response-actions">
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Copy response"
                    aria-label="Copy response"
                    onClick={copy}
                  >
                    {copied ? <Check /> : <Copy />}
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Regenerate request"
                    aria-label="Regenerate request"
                    disabled={pending}
                    onClick={() => submit(current.prompt, [])}
                  >
                    <RotateCcw />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    title={current.saved ? "Unsave request" : "Save request"}
                    aria-label={current.saved ? "Unsave request" : "Save request"}
                    aria-pressed={current.saved}
                    onClick={() => toggleSave(current.id)}
                  >
                    <Bookmark className={current.saved ? "fill-primary/20 text-primary" : ""} />
                  </Button>
                  <span className="action-divider" />
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Positive feedback"
                    aria-label="Positive feedback"
                    aria-pressed={current.feedback === "positive"}
                    onClick={() => feedback(current.id, "positive")}
                  >
                    <ThumbsUp className={current.feedback === "positive" ? "text-primary" : ""} />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Negative feedback"
                    aria-label="Negative feedback"
                    aria-pressed={current.feedback === "negative"}
                    onClick={() => feedback(current.id, "negative")}
                  >
                    <ThumbsDown className={current.feedback === "negative" ? "text-accent" : ""} />
                  </Button>
                  {copied && <span className="text-primary text-xs">Copied</span>}
                  {current.saved && (
                    <span className="text-primary text-xs">Saved in this session</span>
                  )}
                </div>
              </div>
            </div>
          </div>
        )}
        {pending && (
          <div className="request-pending" role="status">
            <LoaderCircle className="animate-spin" size={16} />
            Submitting request
          </div>
        )}
      </div>
      <div className="composer-wrap">
        <form
          className="ai-composer"
          onSubmit={(e) => {
            e.preventDefault();
            send();
          }}
        >
          <textarea
            aria-label="AI request"
            placeholder="What do you want to accomplish?"
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                send();
              }
            }}
            rows={2}
          />
          {files.length > 0 && (
            <div className="attachment-list">
              {files.map((file, i) => (
                <span key={`${file.name}-${i}`} className="attachment-tag">
                  <FileText size={12} />
                  <span>{file.name}</span>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={`Remove ${file.name}`}
                    onClick={() => setFiles((previous) => previous.filter((_, n) => n !== i))}
                  >
                    <X size={12} />
                  </Button>
                </span>
              ))}
            </div>
          )}
          <div className="composer-tools">
            <div className="composer-tools-left">
              <input
                ref={input}
                type="file"
                multiple
                accept="image/*,.pdf,.txt,.md,.csv,.doc,.docx"
                className="sr-only"
                onChange={(e) => {
                  attach(e.target.files);
                  e.target.value = "";
                }}
              />
              <Button
                variant="ghost"
                size="sm"
                onClick={() => input.current?.click()}
                title="Attach files"
              >
                <Paperclip />
                <span>Attach</span>
              </Button>
              <span className="tool-divider" />
              <div className="capability-select">
                <Network size={13} />
                <select
                  aria-label="Request capability"
                  value={capability}
                  onChange={(e) => setCapability(e.target.value as Capability)}
                >
                  {["AUTO", "TEXT", "CODE", "IMAGE", "DOCUMENT"].map((c) => (
                    <option key={c} value={c}>
                      {c === "AUTO" ? "Auto routing" : c}
                    </option>
                  ))}
                </select>
                <ChevronDown size={12} />
              </div>
            </div>
            <div className="composer-tools-right">
              <span className="composer-key">⌘ ↵</span>
              <Button
                type="submit"
                size="icon"
                aria-label="Send request"
                title="Send request"
                disabled={pending || (!prompt.trim() && !files.length)}
              >
                {pending ? <LoaderCircle className="animate-spin" /> : <ArrowUp />}
              </Button>
            </div>
          </div>
        </form>
        {error && (
          <p role="alert" className="composer-error">
            {error}
          </p>
        )}
        <div className="composer-footnote">
          <span>
            <Network size={11} />
            <b>{capability === "AUTO" ? "AUTO ROUTING" : `${capability} ROUTING`}</b>
            <span>
              {capability === "AUTO"
                ? "Let NEXUS select an appropriate available worker."
                : "Capability preference applied to the next request."}
            </span>
          </span>
          <span className="connection-note">
            <span className="tiny-dot" />
            {backend.connected ? "Backend connected" : "Not connected"}
          </span>
        </div>
      </div>
    </main>
  );
}
