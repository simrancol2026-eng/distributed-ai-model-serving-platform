import { createFileRoute } from '@tanstack/react-router';
import { RequestLibrary } from '@/pages/RequestLibrary';
import { pageHead } from '@/utils/metadata';
export const Route = createFileRoute('/history')({head:()=>pageHead('Request History','Your current NEXUS AI workspace session request history.'),component:RequestLibrary});
