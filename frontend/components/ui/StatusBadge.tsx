"use client";

const TONE_MAP: Record<string, { bg: string; text: string; dot: string; border: string }> = {
  COMPLIANT: { bg: "bg-good/10", text: "text-good", dot: "bg-good", border: "border-good/25" },
  HEALTHY: { bg: "bg-good/10", text: "text-good", dot: "bg-good", border: "border-good/25" },
  NON_COMPLIANT: { bg: "bg-warn/10", text: "text-warn", dot: "bg-warn", border: "border-warn/25" },
  WARNING: { bg: "bg-warn/10", text: "text-warn", dot: "bg-warn", border: "border-warn/25" },
  DEGRADED: { bg: "bg-warn/10", text: "text-warn", dot: "bg-warn", border: "border-warn/25" },
  ERROR: { bg: "bg-bad/10", text: "text-bad", dot: "bg-bad", border: "border-bad/25" },
  CRITICAL: { bg: "bg-bad/10", text: "text-bad", dot: "bg-bad", border: "border-bad/25" },
  FAILED: { bg: "bg-bad/10", text: "text-bad", dot: "bg-bad", border: "border-bad/25" },
  QUEUED: { bg: "bg-muted/10", text: "text-muted", dot: "bg-muted", border: "border-muted/25" },
  RUNNING: { bg: "bg-accent/15", text: "text-accent", dot: "bg-accent", border: "border-accent/30" },
  COMPLETE: { bg: "bg-good/10", text: "text-good", dot: "bg-good", border: "border-good/25" },
  BLOCKED: { bg: "bg-bad/10", text: "text-bad", dot: "bg-bad", border: "border-bad/25" },
};

const LABEL_MAP: Record<string, string> = {
  COMPLIANT: "Compliant",
  NON_COMPLIANT: "At Risk",
  ERROR: "Critical",
  HEALTHY: "Healthy",
  WARNING: "Warning",
  DEGRADED: "Degraded",
  CRITICAL: "Critical",
  QUEUED: "Queued",
  RUNNING: "Running",
  COMPLETE: "Completed",
  FAILED: "Failed",
  BLOCKED: "Blocked",
};

export function StatusBadge({
  value,
  variant = "pill",
}: {
  value: string;
  variant?: "pill" | "inline";
}) {
  const tone = TONE_MAP[value] ?? {
    bg: "bg-muted/10",
    text: "text-muted",
    dot: "bg-muted",
    border: "border-muted/20",
  };
  const label = LABEL_MAP[value] ?? value;

  if (variant === "inline") {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs font-medium">
        <span className={`h-1.5 w-1.5 rounded-full ${tone.dot}`} />
        <span className={tone.text}>{label}</span>
      </span>
    );
  }

  const isRunning = value === "RUNNING";

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium ${tone.bg} ${tone.text} ${tone.border}`}
    >
      <span className="relative flex h-1.5 w-1.5">
        {isRunning && (
          <span className={`absolute inline-flex h-full w-full animate-ping rounded-full opacity-75 ${tone.dot}`} />
        )}
        <span className={`relative inline-flex h-1.5 w-1.5 rounded-full ${tone.dot}`} />
      </span>
      <span>{label}</span>
    </span>
  );
}