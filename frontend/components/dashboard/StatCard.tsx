"use client";

import { LucideIcon } from "lucide-react";

export function StatCard({
  icon: Icon,
  label,
  value,
  tone = "accent",
  caption,
}: {
  icon: LucideIcon;
  label: string;
  value: string | number;
  tone?: "accent" | "good" | "warn" | "bad";
  caption?: string;
}) {
  const toneClasses: Record<string, string> = {
    accent: "bg-accent/10 text-accent",
    good: "bg-good/10 text-good",
    warn: "bg-warn/10 text-warn",
    bad: "bg-bad/10 text-bad",
  };

  return (
    <div className="panel flex min-w-0 items-center gap-3 p-4">
      <div
        className={`flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg ${toneClasses[tone]}`}
      >
        <Icon size={18} strokeWidth={2} />
      </div>
      <div className="min-w-0 flex-1">
        <div className="text-2xl font-bold leading-tight text-ink">{value}</div>
        <div className="truncate text-xs text-muted">{label}</div>
        {caption && <div className="mt-0.5 truncate text-[11px] text-muted">{caption}</div>}
      </div>
    </div>
  );
}