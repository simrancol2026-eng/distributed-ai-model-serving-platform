import { useState, useEffect, type ReactNode } from "react";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import {
  Plus,
  PanelLeftClose,
  PanelLeft,
  MessageSquare,
  History,
  Bookmark,
  Settings2,
  Sun,
  Moon,
  UserRound,
  ChevronDown,
  PanelRight,
  X,
  CircleHelp,
  ArrowUpRight,
  LogOut,
} from "lucide-react";
import { NexusLogo } from "@/components/NexusLogo";
import { capabilities, platformNav } from "@/data/platform";
import { useWorkspace } from "@/hooks/use-workspace";
import { useBackendHealth } from "@/hooks/use-backend-health";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/StatusBadge";
import { RoutingPanel } from "@/components/RoutingPanel";
import { Dialog, DialogContent, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import { Sheet, SheetContent, SheetTitle, SheetDescription } from "@/components/ui/sheet";
export function AppShell({ children }: { children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(false),
    [mobileNav, setMobileNav] = useState(false),
    [routerOpen, setRouterOpen] = useState(true),
    [routerDrawer, setRouterDrawer] = useState(false),
    [settings, setSettings] = useState(false),
    [user, setUser] = useState(false),
    [light, setLight] = useState(false);
  const path = useRouterState({ select: (s) => s.location.pathname });
  const navigate = useNavigate();
  const workspace = useWorkspace();
  const backend = useBackendHealth();
  useEffect(() => {
    document.documentElement.classList.toggle("light", light);
  }, [light]);
  useEffect(() => {
    setMobileNav(false);
  }, [path]);
  const isWorkspace = path === "/";
  function newRequest() {
    workspace.newRequest();
    navigate({ to: "/" });
    setMobileNav(false);
  }
  function sidebar() {
    return (
      <>
        <div className="sidebar-brand">
          <Link to="/" aria-label="NEXUS AI workspace">
            <NexusLogo size={30} />
            <span>
              NEXUS AI<small>INTELLIGENCE, ORCHESTRATED</small>
            </span>
          </Link>
          <Button
            variant="ghost"
            size="icon"
            title="Collapse sidebar"
            aria-label="Collapse sidebar"
            onClick={() => setCollapsed(!collapsed)}
          >
            <PanelLeftClose />
          </Button>
        </div>
        <Button className="new-request" onClick={newRequest}>
          <Plus />
          <span>New request</span>
          <small>⌘ N</small>
        </Button>
        <div className="sidebar-group">
          <p className="eyebrow">WORKSPACE</p>
          {[
            { to: "/", label: "Workspace", icon: MessageSquare },
            { to: "/history", label: "History", icon: History },
            { to: "/saved", label: "Saved", icon: Bookmark },
          ].map((n) => (
            <Button asChild variant="ghost" key={n.to} className="side-nav">
              <Link
                to={n.to as "/" | "/history" | "/saved"}
                activeOptions={{ exact: true }}
                activeProps={{ className: "active" }}
                title={n.label}
              >
                <n.icon />
                <span>{n.label}</span>
                {n.to === "/history" && workspace.entries.length > 0 && (
                  <small>{workspace.entries.length}</small>
                )}
              </Link>
            </Button>
          ))}
        </div>
        <div className="sidebar-group">
          <p className="eyebrow">CAPABILITIES</p>
          {capabilities.map((c) => (
            <Button
              key={c.id}
              variant="ghost"
              className={`side-nav ${workspace.capability === c.id && isWorkspace ? "capability-active" : ""}`}
              title={c.title}
              onClick={() => {
                workspace.setCapability(c.id);
                navigate({ to: "/" });
                setMobileNav(false);
              }}
            >
              <c.icon className={c.tone} />
              <span>{c.title}</span>
              <span className="nav-capability-dot" />
            </Button>
          ))}
        </div>
        <div className="sidebar-group platform-group">
          <p className="eyebrow">PLATFORM</p>
          {platformNav.map((n) => (
            <Button key={n.to} asChild variant="ghost" className="side-nav">
              <Link to={n.to} activeProps={{ className: "active" }} title={n.label}>
                <n.icon />
                <span>{n.label}</span>
              </Link>
            </Button>
          ))}
        </div>
        <div className="sidebar-footer">
          <Button
            variant="ghost"
            className="side-nav"
            onClick={() => setSettings(true)}
            title="Settings"
          >
            <Settings2 />
            <span>Settings</span>
          </Button>
          <Button
            variant="ghost"
            className="side-nav"
            onClick={() => setLight(!light)}
            title="Toggle theme"
          >
            {light ? <Moon /> : <Sun />}
            <span>Appearance</span>
            <small>{light ? "Light" : "Dark"}</small>
          </Button>
          <Button
            variant="ghost"
            className="user-button"
            onClick={() => setUser(true)}
            title="User account"
          >
            <span className="user-avatar">
              <UserRound size={16} />
            </span>
            <span>
              Personal workspace<small>Local session</small>
            </span>
            <ChevronDown size={12} />
          </Button>
        </div>
      </>
    );
  }
  return (
    <div className={`app-shell ${collapsed ? "sidebar-collapsed" : ""}`}>
      <aside className="app-sidebar">{sidebar()}</aside>
      <div className="app-main">
        <header className="app-topbar">
          <div className="topbar-left">
            <Button
              className="mobile-nav-toggle"
              variant="ghost"
              size="icon"
              aria-label="Open navigation"
              onClick={() => setMobileNav(true)}
            >
              <PanelLeft />
            </Button>
            {collapsed && (
              <Button
                variant="ghost"
                size="icon"
                aria-label="Expand sidebar"
                onClick={() => setCollapsed(false)}
              >
                <PanelLeft />
              </Button>
            )}
            <Link to="/" className="topbar-product">
              NEXUS <span>AI</span>
            </Link>
          </div>
          <nav className="topbar-nav">
            <Link to="/" activeOptions={{ exact: true }} activeProps={{ className: "active" }}>
              Workspace
            </Link>
            {platformNav.slice(0, 4).map((n) => (
              <Link key={n.to} to={n.to} activeProps={{ className: "active" }}>
                {n.label}
              </Link>
            ))}
          </nav>
          <div className="topbar-actions">
            <span className="topbar-session">
              {isWorkspace ? (workspace.currentId ? "Conversation" : "New request") : "Platform"}{" "}
              <span>/</span> {isWorkspace ? "Intelligent workspace" : "NEXUS AI"}
            </span>
            <StatusBadge
              label={backend.connected ? "Backend connected" : "Not connected"}
              online={backend.connected}
            />
            <Button
              variant="ghost"
              size="icon"
              title="Settings"
              aria-label="Settings"
              onClick={() => setSettings(true)}
            >
              <Settings2 />
            </Button>
            {isWorkspace && (
              <>
                <Button
                  variant="ghost"
                  size="icon"
                  className="desktop-router-toggle"
                  aria-label="Toggle routing panel"
                  title="Toggle routing panel"
                  onClick={() => setRouterOpen(!routerOpen)}
                >
                  <PanelRight className={routerOpen ? "text-primary" : ""} />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="drawer-router-toggle"
                  aria-label="Open routing panel"
                  title="Open routing panel"
                  onClick={() => setRouterDrawer(true)}
                >
                  <PanelRight />
                </Button>
              </>
            )}
          </div>
        </header>
        <div className={`app-content ${isWorkspace && routerOpen ? "has-router" : ""}`}>
          <div className="page-outlet">{children}</div>
          {isWorkspace && routerOpen && (
            <aside className="desktop-router">
              <RoutingPanel />
            </aside>
          )}
        </div>
      </div>
      <Sheet open={mobileNav} onOpenChange={setMobileNav}>
        <SheetContent side="left" className="mobile-sidebar">
          <SheetTitle className="sr-only">NEXUS navigation</SheetTitle>
          <SheetDescription className="sr-only">Workspace and platform navigation</SheetDescription>
          {sidebar()}
        </SheetContent>
      </Sheet>
      <Sheet open={routerDrawer} onOpenChange={setRouterDrawer}>
        <SheetContent className="router-drawer">
          <SheetTitle className="sr-only">Intelligent router</SheetTitle>
          <SheetDescription className="sr-only">Current request routing details</SheetDescription>
          <RoutingPanel />
        </SheetContent>
      </Sheet>
      <Dialog open={settings} onOpenChange={setSettings}>
        <DialogContent>
          <DialogTitle>Workspace settings</DialogTitle>
          <DialogDescription>NEXUS AI · Distributed AI Model Serving Platform</DialogDescription>
          <div className="settings-row">
            <span>Backend connection</span>
            <StatusBadge />
          </div>
          <div className="settings-row">
            <span>Default routing</span>
            <span className="mono-tag">AUTO</span>
          </div>
          <div className="settings-row">
            <span>Appearance</span>
            <Button variant="secondary" size="sm" onClick={() => setLight(!light)}>
              {light ? <Moon /> : <Sun />}
              {light ? "Switch to dark" : "Switch to light"}
            </Button>
          </div>
          <div className="settings-row">
            <span>Session storage</span>
            <span className="text-muted-foreground text-sm">In memory</span>
          </div>
        </DialogContent>
      </Dialog>
      <Dialog open={user} onOpenChange={setUser}>
        <DialogContent>
          <DialogTitle>Personal workspace</DialogTitle>
          <DialogDescription>Local session · Not connected to an account</DialogDescription>
          <div className="settings-row">
            <span>Requests this session</span>
            <strong>{workspace.entries.length}</strong>
          </div>
          <div className="settings-row">
            <span>Saved this session</span>
            <strong>{workspace.entries.filter((e) => e.saved).length}</strong>
          </div>
          <div className="settings-row">
            <span>Account</span>
            <StatusBadge />
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
