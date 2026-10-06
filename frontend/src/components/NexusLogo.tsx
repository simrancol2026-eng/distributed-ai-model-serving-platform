export function NexusLogo({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <g className="stroke-primary" strokeWidth="1.6">
        <line x1="16" y1="16" x2="16" y2="4" />
        <line x1="16" y1="16" x2="28" y2="16" />
        <line x1="16" y1="16" x2="16" y2="28" />
        <line x1="16" y1="16" x2="4" y2="16" />
      </g>
      <polygon
        points="16,10.5 20.8,13.25 20.8,18.75 16,21.5 11.2,18.75 11.2,13.25"
        className="fill-background stroke-primary"
        strokeWidth="1.6"
      />
      <circle cx="16" cy="16" r="1.8" className="fill-primary" />
      <g className="fill-accent">
        <circle cx="16" cy="4" r="2.4" />
        <circle cx="28" cy="16" r="2.4" />
        <circle cx="16" cy="28" r="2.4" />
        <circle cx="4" cy="16" r="2.4" />
      </g>
    </svg>
  );
}
