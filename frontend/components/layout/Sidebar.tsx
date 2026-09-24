"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import {
  ShieldCheck,
  LayoutGrid,
  MonitorSmartphone,
  ListChecks,
  History,
  Cpu,
  Boxes,
  Network,
  Radio,
  Server,
} from "lucide-react";
import { getToken } from "@/lib/api";

const NAV_SECTIONS = [
  {
    title: "MONITORING",
    items: [
      { href: "/overview", label: "Command Center", icon: LayoutGrid },
      { href: "/endpoints", label: "Endpoints Directory", icon: MonitorSmartphone },
      { href: "/compliance", label: "Compliance Matrix", icon: ShieldCheck },
      { href: "/hardware", label: "Hardware Telemetry", icon: Cpu },
    ],
  },
  {
    title: "INVENTORY",
    items: [
      { href: "/applications", label: "Installed Software", icon: Boxes },
      { href: "/ports", label: "Listening Ports", icon: Network },
    ],
  },
  {
    title: "OPERATIONS",
    items: [
      { href: "/jobs", label: "Assessment Queue", icon: ListChecks },
      { href: "/audit", label: "ISE Action Audit", icon: History },
    ],
  },
];

function SystemStatus() {
  const [online, setOnline] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    const check = async () => {
      try {
        const token = getToken();
        const res = await fetch("/api/v1/endpoints", {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
          cache: "no-store",
        });
        if (!cancelled) setOnline(res.ok);
      } catch {
        if (!cancelled) setOnline(false);
      }
    };
    check();
    const t = setInterval(check, 15000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, []);

  return (
    <div className="space-y-2 p-4">
      <div className="flex items-center justify-between text-[11px] text-muted">
        <span className="flex items-center gap-1.5">
          <Server size={12} className="text-muted" />
          Backend API
        </span>
        <span className="flex items-center gap-1.5 font-medium">
          <span
            className={`h-1.5 w-1.5 rounded-full ${
              online === null ? "bg-muted" : online ? "bg-good" : "bg-bad"
            }`}
          />
          <span className={online ? "text-good" : "text-muted"}>
            {online === null ? "Checking" : online ? "Online" : "Disconnected"}
          </span>
        </span>
      </div>

      <div className="flex items-center justify-between text-[11px] text-muted">
        <span className="flex items-center gap-1.5">
          <Radio size={12} className="text-accent" />
          ISE Session Watcher
        </span>
        <span className="flex items-center gap-1.5 font-medium text-accent">
          <span className="relative flex h-1.5 w-1.5">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-accent opacity-75" />
            <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-accent" />
          </span>
          Polling
        </span>
      </div>
    </div>
  );
}

export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="flex h-screen w-64 flex-shrink-0 flex-col border-r border-border bg-panel2">
      {/* Platform Header — click to go home */}
      <Link
        href="/overview"
        className="flex items-center gap-3 border-b border-border px-5 py-5 transition hover:bg-ink/[0.03]"
      >
        <div className="relative flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-accent/15 text-accent shadow-sm">
          <ShieldCheck size={22} className="text-accent" />
          <span className="absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full border-2 border-panel2 bg-good" />
        </div>
        <div className="min-w-0 flex-1 leading-tight">
          <div className="flex items-center gap-1.5">
            <span className="truncate text-sm font-bold text-ink">PostureEngine</span>
            <span className="rounded bg-accent/15 px-1 py-0.2 text-[9px] font-semibold text-accent">
              ISE
            </span>
          </div>
          <div className="truncate text-[11px] text-muted">Endpoint Zero-Trust</div>
        </div>
      </Link>

      {/* Navigation Sections */}
      <nav className="flex-1 space-y-6 overflow-y-auto px-4 py-5">
        {NAV_SECTIONS.map((sec) => (
          <div key={sec.title}>
            <div className="mb-2 px-2 text-[10px] font-semibold uppercase tracking-wider text-muted/70">
              {sec.title}
            </div>
            <div className="space-y-1">
              {sec.items.map((item) => {
                const active = pathname === item.href || (item.href !== "/overview" && pathname?.startsWith(item.href));
                const Icon = item.icon;
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={`group relative flex items-center gap-2.5 rounded-lg px-3 py-2.5 text-xs font-medium transition ${
                      active
                        ? "bg-accent/15 text-accent shadow-xs"
                        : "text-muted hover:bg-ink/[0.04] hover:text-ink"
                    }`}
                  >
                    {active && (
                      <span className="absolute left-0 top-1.5 bottom-1.5 w-1 rounded-r-full bg-accent" />
                    )}
                    <Icon
                      size={16}
                      className={active ? "text-accent" : "text-muted group-hover:text-ink"}
                    />
                    <span className="truncate">{item.label}</span>
                  </Link>
                );
              })}
            </div>
          </div>
        ))}
      </nav>

      {/* System Live Heartbeat */}
      <div className="border-t border-border bg-panel/30">
        <SystemStatus />
      </div>
    </aside>
  );
}