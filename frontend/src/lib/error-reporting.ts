function describeAppError(error: unknown): string {
  if (error instanceof Response) {
    return `Response ${error.status}${error.url ? ` at ${error.url}` : ""}`;
  }
  if (error instanceof Error) {
    return error.stack ?? `${error.name}: ${error.message}`;
  }
  return String(error);
}

export function reportAppError(error: unknown, context: Record<string, unknown> = {}) {
  const route = typeof window === "undefined" ? undefined : window.location.pathname;
  console.error(describeAppError(error), {
    source: "react_error_boundary",
    ...(route !== undefined ? { route } : {}),
    ...context,
  });
}
