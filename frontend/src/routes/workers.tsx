import { createFileRoute } from '@tanstack/react-router';
import { WorkerFleet, workersQuery } from '@/pages/WorkerFleet';
import { pageHead } from '@/utils/metadata';
export const Route = createFileRoute('/workers')({head:()=>pageHead('Worker Fleet','Inspect NEXUS AI compute worker availability, capacity and latency.'),loader:({context})=>context.queryClient.ensureQueryData(workersQuery),component:WorkerFleet});
