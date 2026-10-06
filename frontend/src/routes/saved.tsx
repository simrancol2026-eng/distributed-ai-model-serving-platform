import { createFileRoute } from '@tanstack/react-router';
import { RequestLibrary } from '@/pages/RequestLibrary';
import { pageHead } from '@/utils/metadata';
export const Route = createFileRoute('/saved')({head:()=>pageHead('Saved Requests','Saved requests in your current NEXUS AI workspace session.'),component:()=> <RequestLibrary saved/>});
