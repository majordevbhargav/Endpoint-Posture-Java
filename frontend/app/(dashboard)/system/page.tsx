"use client";

import { useEffect, useState } from "react";
import { Activity, AlertTriangle, Database, Layers, RefreshCw, Radio, Users } from "lucide-react";
import { api, SystemHealth } from "@/lib/api";
import { usePolling } from "@/lib/usePolling";

const STATUS_STYLE: Record<SystemHealth["status"], { box: string; label: string }> = {
  UP: { box: "border-good/30 bg-good/10 text-good", label: "All systems operational" },
  DEGRADED: { box: "border-warn/30 bg-warn/10 text-warn", label: "Degraded" },
  DOWN: { box: "border-bad/30 bg-bad/10 text-bad", label: "Down" },
};

/** "45s", "12m", "3h 5m" from a number of seconds. */
function formatAge(secs: number | null): string {
  if (secs === null) return "none waiting";
  if (secs < 60) return `${secs}s`;
  const m = Math.floor(secs / 60);
  if (m < 60) return `${m}m`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString() : "never");

function Tile({
  title,
  icon: Icon,
  ok,
  value,
  caption,
}: {
  title: string;
  icon: typeof Database;
  ok: boolean;
  value: string;
  caption?: string;
}) {
  return (
    <div className="panel p-4">
      <div className="flex items-center justify-between text-xs text-muted">
        <span>{title}</span>
        <Icon size={16} className={ok ? "text-good" : "text-bad"} />
      </div>
      <div className={`mt-2 text-lg font-bold ${ok ? "text-good" : "text-bad"}`}>{value}</div>
      {caption && <div className="mt-0.5 break-words text-[11px] text-muted">{caption}</div>}
    </div>
  );
}

export default function SystemHealthPage() {
  const [health, setHealth] = useState<SystemHealth | null>(null);
  const [loading, setLoading] = useState(false);
  const [unreachable, setUnreachable] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setHealth(await api.systemHealth());
      setUnreachable(false);
    } catch {
      setUnreachable(true); // keep the last good snapshot on screen
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  usePolling(load, 10000);

  const s = health ? STATUS_STYLE[health.status] : null;

  return (
    <div className="space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">System Health</h1>
          <p className="mt-1 text-xs text-muted">
            Database, Cisco ISE polling, the job queue and the worker pool. Refreshes every 10 seconds.
          </p>
        </div>
        <button
          onClick={load}
          disabled={loading}
          className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:border-accent/40 disabled:opacity-50"
        >
          <RefreshCw size={13} className={loading ? "animate-spin text-accent" : "text-muted"} />
          <span>Refresh</span>
        </button>
      </div>

      {unreachable && (
        <div className="rounded-lg border border-bad/30 bg-bad/10 p-3 text-xs text-bad">
          Can&apos;t reach the backend. {health ? "Showing the last snapshot." : "Check that it is running on port 8090."}
        </div>
      )}

      {!health && !unreachable && <div className="py-12 text-center text-xs text-muted">Loading system health…</div>}

      {health && s && (
        <>
          <div className={`rounded-xl border p-4 ${s.box}`}>
            <div className="flex items-center gap-2 text-sm font-bold">
              <Activity size={16} />
              <span>{s.label}</span>
              <span className="ml-auto text-[11px] font-normal opacity-80">
                Checked {new Date(health.checkedAt).toLocaleTimeString()}
              </span>
            </div>
            {health.warnings.length > 0 && (
              <ul className="mt-2 space-y-1 text-xs">
                {health.warnings.map((w) => (
                  <li key={w} className="flex items-start gap-1.5">
                    <AlertTriangle size={12} className="mt-0.5 flex-shrink-0" />
                    <span>{w}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Tile
              title="Database"
              icon={Database}
              ok={health.database.reachable}
              value={health.database.reachable ? "Reachable" : "Unreachable"}
              caption={health.database.error ?? undefined}
            />
            <Tile
              title="Cisco ISE poll"
              icon={Radio}
              ok={health.ise.reachable}
              value={health.ise.reachable ? "Polling" : "Unreachable"}
              caption={
                health.ise.reachable
                  ? `Last success ${when(health.ise.lastSuccessAt)}`
                  : `${health.ise.lastError ?? "No detail"} (last success ${when(health.ise.lastSuccessAt)})`
              }
            />
            <Tile
              title="Worker pool"
              icon={Users}
              ok={health.workers.enabled}
              value={health.workers.enabled ? `${health.workers.threads} thread${health.workers.threads === 1 ? "" : "s"}` : "Disabled"}
              caption={`${health.queue.running} running now`}
            />
            <Tile
              title="Oldest queued job"
              icon={Layers}
              ok={(health.queue.oldestQueuedAgeSeconds ?? 0) <= 600}
              value={formatAge(health.queue.oldestQueuedAgeSeconds)}
              caption={`${health.queue.queued} queued in total`}
            />
          </div>

          <div className="panel p-5">
            <div className="mb-4 text-xs font-semibold uppercase tracking-wider text-muted">Job queue</div>
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-5">
              {[
                ["Queued", health.queue.queued, "text-ink"],
                ["Running", health.queue.running, "text-accent"],
                ["Complete", health.queue.complete, "text-good"],
                ["Failed (all time)", health.queue.failed, "text-bad"],
                ["Failed (24h)", health.queue.failedLast24h, health.queue.failedLast24h > 0 ? "text-bad" : "text-ink"],
              ].map(([label, value, color]) => (
                <div key={label as string} className="rounded-lg bg-base/60 p-3">
                  <div className="text-[11px] text-muted">{label}</div>
                  <div className={`mt-1 font-mono text-2xl font-bold ${color}`}>{value}</div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}