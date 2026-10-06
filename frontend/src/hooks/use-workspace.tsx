import { createContext, useContext, useState, type ReactNode } from 'react';
import type { Capability, RequestEntry } from '@/types/platform';
import { platformService } from '@/services/platform';
type WorkspaceState = {
 capability: Capability; setCapability: (capability: Capability) => void;
 entries: RequestEntry[]; currentId: string | null; pending: boolean;
 submit: (prompt: string, files: File[]) => Promise<void>;
 newRequest: () => void; openRequest: (id: string) => void;
 toggleSave: (id: string) => void; feedback: (id: string, value: 'positive' | 'negative') => void;
};
const WorkspaceContext = createContext<WorkspaceState | null>(null);
export function WorkspaceProvider({ children }: { children: ReactNode }) {
 const [capability, setCapability] = useState<Capability>('AUTO');
 const [entries, setEntries] = useState<RequestEntry[]>([]);
 const [currentId, setCurrentId] = useState<string | null>(null);
 const [pending, setPending] = useState(false);
 async function submit(prompt: string, files: File[]) {
  if (pending || (!prompt.trim() && !files.length)) return;
  setPending(true);
  try {
   const result = await platformService.infer(prompt.trim(), capability, files);
   const entry: RequestEntry = { id: crypto.randomUUID(), prompt: prompt.trim() || 'Analyze attached files.', capability, files: files.map(f => f.name), createdAt: Date.now(), result, saved: false };
   setEntries(previous => [...previous, entry]); setCurrentId(entry.id);
  } finally { setPending(false); }
 }
 return <WorkspaceContext.Provider value={{ capability, setCapability, entries, currentId, pending, submit, newRequest: () => { setCurrentId(null); setCapability('AUTO'); }, openRequest: setCurrentId, toggleSave: id => setEntries(previous => previous.map(e => e.id === id ? { ...e, saved: !e.saved } : e)), feedback: (id, value) => setEntries(previous => previous.map(e => e.id === id ? { ...e, feedback: value } : e)) }}>{children}</WorkspaceContext.Provider>;
}
export function useWorkspace() { const context = useContext(WorkspaceContext); if (!context) throw new Error('Workspace provider is required'); return context; }
