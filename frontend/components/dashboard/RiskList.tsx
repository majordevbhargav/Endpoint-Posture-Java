"use client";

import Link from "next/link";
import { ChevronRight } from "lucide-react";

export type RiskSeverity = "Critical" | "High" | "Medium";

export interface RiskItem {
  id: string;
  severity: RiskSeverity;
  title: string;
  subtitle: string;
}

const SEVERITY_STYLE: Record<RiskSeverity, string> = {
  Critical: "bg-bad/15 text-bad",
  High: "bg-warn/15 text-warn",
  Medium: "bg-medium/15 text-medium",
};

export function RiskList({ items }: { items: RiskItem[] }) {
  if (items.length === 0) {
    return (
      <div className="py-6 text-center text-sm text-muted">
        No open risks — every endpoint is compliant.
      </div>
    );
  }

  return (
    <div className="flex flex-col divide-y divide-border/60">
      {items.map((item) => (
        <Link
          key={item.id}
          href={`/endpoints/${item.id}`}
          className="group flex items-center gap-3 py-3 first:pt-0 last:pb-0"
        >
          <span
            className={`flex-shrink-0 rounded px-2 py-0.5 text-[10px] font-semibold ${SEVERITY_STYLE[item.severity]}`}
          >
            {item.severity}
          </span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm text-ink">{item.title}</div>
            <div className="truncate text-xs text-muted">{item.subtitle}</div>
          </div>
          <ChevronRight size={14} className="flex-shrink-0 text-muted transition group-hover:text-accent" />
        </Link>
      ))}
    </div>
  );
}