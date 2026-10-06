import { createFileRoute } from '@tanstack/react-router';
import { RoutingPipeline } from '@/pages/RoutingPipeline';
import { pageHead } from '@/utils/metadata';
export const Route = createFileRoute('/routing')({head:()=>pageHead('Intelligent Routing','NEXUS AI routing architecture: capability detection, worker health, workload and resource selection.'),component:RoutingPipeline});
