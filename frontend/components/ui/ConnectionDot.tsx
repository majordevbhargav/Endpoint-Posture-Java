// components/ui/ConnectionDot.tsx
export function ConnectionDot({ connected }: { connected: boolean }) {
  if (connected) {
    return (
      <span className="inline-flex items-center gap-2 text-xs font-medium text-good">
        <span className="relative flex h-2 w-2">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-good opacity-75" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-good" />
        </span>
        <span>ISE Active</span>
      </span>
    );
  }

  return (
    <span className="inline-flex items-center gap-2 text-xs font-normal text-muted">
      <span className="h-2 w-2 rounded-full bg-border" />
      <span>Disconnected</span>
    </span>
  );
}