"use client";

import { CheckCircle2, ShieldAlert, ShieldCheck, ShieldX } from "lucide-react";

export interface ActivityItem {
  id: string;
  time: string; // ISO
  title: string;
  subtitle?: string;
  kind: "share" | "restrict" | "clear" | "other";
}

const ICONS: Record<ActivityItem["kind"], typeof CheckCircle2> = {
  share: ShieldCheck,
  restrict: ShieldX,
  clear: ShieldAlert,
  other: CheckCircle2,
};

const TONES: Record<ActivityItem["kind"], string> = {
  share: "bg-accent/10 text-accent",
  restrict: "bg-bad/10 text-bad",
  clear: "bg-good/10 text-good",
  other: "bg-muted/10 text-muted",
};

export function ActivityFeed({ items }: { items: ActivityItem[] }) {
  if (items.length === 0) {
    return <div className="py-6 text-center text-sm text-muted">No activity recorded yet.</div>;
  }

  return (
    <div className="flex flex-col gap-4">
      {items.map((item) => {
        const Icon = ICONS[item.kind];
        return (
          <div key={item.id} className="flex gap-3">
            <div
              className={`mt-0.5 flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full ${TONES[item.kind]}`}
            >
              <Icon size={13} />
            </div>
            <div className="min-w-0">
              <div className="text-xs text-muted">
                {new Date(item.time).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
              </div>
              <div className="text-sm text-ink">{item.title}</div>
              {item.subtitle && (
                <p className="min-w-0 break-words text-xs text-muted">
                  {item.subtitle}
                </p>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}