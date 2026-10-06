"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  Cpu, Battery, RefreshCw, Play, Monitor, Search, AlertTriangle, ChevronDown, ChevronUp, Wrench,
} from "lucide-react";
import { api, HardwareListItem, HardwareSummary } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { PaginationBar } from "@/components/ui/PaginationBar";
import { usePolling } from "@/lib/usePolling";
import { can, DENIED_HINT } from "@/lib/permissions";

type Recommendation = { priority: string; area: string; action: string };

function ScoreBar({ value }: { value: number | null | undefined }) {
  if (value == null) return <div className="text-xs text-muted">—</div>;
  const barColor = value >= 80 ? "bg-good" : value >= 60 ? "bg-warn" : "bg-bad";
  return (
    <div className="flex min-w-[70px] flex-col gap-1">
      <span className="font-mono text-[11px] font-bold leading-none">{value}</span>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-border/60">
        <div className={`h-full rounded-full ${barColor}`} style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
      </div>
    </div>
  );
}

const shortTime = (iso: string) =>
  new Date(iso).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });

export default function HardwarePage() {
  const [items, setItems] = useState<HardwareListItem[] | null>(null);
  const [total, setTotal] = useState(0);
  const [summary, setSummary] = useState<HardwareSummary | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState("");
  const [debounced, setDebounced] = useState("");
  const [bandFilter, setBandFilter] = useState("ALL");
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [recos, setRecos] = useState<Record<string, Recommendation[]>>({});
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  // UI convenience only: the backend @PreAuthorize rule is the real control.
  const mayEnqueue = can("enqueue");

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(search.trim());
      setPage(1);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [res, sum] = await Promise.all([
        api.hardwarePage({ page: page - 1, size: pageSize, q: debounced, band: bandFilter }),
        api.hardwareSummary().catch(() => null),
      ]);
      setItems(res.items);
      setTotal(res.total);
      setSummary(sum);
    } catch {
      setItems((prev) => prev ?? []); // keep the last good page on a failed poll
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, debounced, bandFilter]);

  useEffect(() => {
    load();
  }, [load]);

  usePolling(load, 20000);

  async function toggleInspect(id: string) {
    if (expandedId === id) { setExpandedId(null); return; }
    setExpandedId(id);
    if (!recos[id]) {
      const hw = await api.latestHardwareOrNull(id).catch(() => null);
      setRecos((prev) => ({ ...prev, [id]: hw?.recommendations ?? [] }));
    }
  }

  async function triggerHardwareAll() {
    if (!mayEnqueue) return;
    setActionNotice("Queuing hardware health checks for connected endpoints…");
    try {
      const res = await api.bulkEnqueue("HARDWARE_CHECK");
      setActionNotice(
        res.queued === 0
          ? "Nothing to queue: every connected device already has a check pending."
          : `Queued ${res.queued} hardware health checks.`
      );
      load();
    } catch (e) {
      setActionNotice(e instanceof Error ? e.message : "Could not queue the checks.");
    }
    setTimeout(() => setActionNotice(null), 5000);
  }

  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);

  return (
    <div className="space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Hardware Health Telemetry</h1>
          <p className="mt-1 text-xs text-muted">
            CPU utilization, memory pressure, storage life and battery health, worst devices first. If the newest check
            failed, the last successful scores are shown with a warning. Search and filters run on the server.
          </p>
        </div>
        <div className="flex items-center gap-2.5">
          <button
            onClick={load}
            disabled={loading}
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:border-accent/40 disabled:opacity-50"
          >
            <RefreshCw size={13} className={loading ? "animate-spin text-accent" : "text-muted"} />
            <span>Refresh</span>
          </button>
          <button
            onClick={triggerHardwareAll}
            disabled={!mayEnqueue}
            title={!mayEnqueue ? DENIED_HINT : undefined}
            className="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-base transition hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <Play size={13} />
            <span>Check Connected Hardware</span>
          </button>
        </div>
      </div>

      {actionNotice && (
        <div className="rounded-lg border border-accent/30 bg-accent/10 px-4 py-2 text-xs font-medium text-accent">
          {actionNotice}
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Fleet Average Health</span><Cpu size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">
            {summary?.avgScore ?? 0} <span className="text-xs text-muted">/100</span>
          </div>
          <div className="text-[11px] text-muted">{summary?.withReport ?? 0} devices with a good run</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Critical / Degraded</span><AlertTriangle size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{summary?.criticalOrDegraded ?? 0}</div>
          <div className="text-[11px] text-muted">Require maintenance</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Battery Warnings</span><Battery size={16} className="text-warn" />
          </div>
          <div className="mt-2 text-2xl font-bold text-warn">{summary?.batteryWarnings ?? 0}</div>
          <div className="text-[11px] text-muted">Battery health &lt; 70%</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Open Recommendations</span><Wrench size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{summary?.recommendations ?? 0}</div>
          <div className="text-[11px] text-muted">Actionable suggestions</div>
        </div>
      </div>

      <div className="panel flex flex-col justify-between gap-3 p-3 sm:flex-row sm:items-center">
        <div className="relative max-w-md flex-1">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by device, MAC, or model…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
          />
        </div>
        <select
          value={bandFilter}
          onChange={(e) => { setBandFilter(e.target.value); setPage(1); }}
          className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
        >
          <option value="ALL">All Health Bands</option>
          <option value="HEALTHY">Healthy</option>
          <option value="WARNING">Warning</option>
          <option value="DEGRADED">Degraded</option>
          <option value="CRITICAL">Critical</option>
          <option value="LAST_FAILED">Latest Attempt Failed</option>
          <option value="FAILED">Never Collected (Failed)</option>
          <option value="NO_REPORT">No Report</option>
        </select>
      </div>

      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[840px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="px-4 py-3">Device</th>
                <th className="px-4 py-3">Overall Band</th>
                <th className="px-4 py-3">CPU Score</th>
                <th className="px-4 py-3">Memory Score</th>
                <th className="px-4 py-3">Storage Score</th>
                <th className="px-4 py-3">Battery Score</th>
                <th className="px-4 py-3">Recommendations</th>
                <th className="px-4 py-3">Last Collected</th>
                <th className="px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {items === null && (
                <tr><td colSpan={9} className="py-12 text-center text-muted">Loading hardware telemetry records…</td></tr>
              )}
              {items !== null && items.length === 0 && (
                <tr><td colSpan={9} className="py-12 text-center text-muted">No matching hardware reports found.</td></tr>
              )}
              {(items ?? []).flatMap((h) => {
                const isExpanded = expandedId === h.endpointId;
                const rows = [
                  <tr key={h.endpointId} className="transition hover:bg-ink/[0.02]">
                    <td className="px-4 py-3 font-medium text-ink">
                      <Link href={`/endpoints/${h.endpointId}`} className="flex items-center gap-1.5 font-mono text-accent hover:underline">
                        <Monitor size={13} className="text-muted" />
                        <span>{h.hostname ?? h.macAddress}</span>
                      </Link>
                      {h.model && (
                        <div className="max-w-[180px] truncate text-[10px] text-muted">
                          {h.manufacturer ? `${h.manufacturer} ` : ""}{h.model}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {h.state === "NO_REPORT" ? (
                        <span className="text-[11px] text-muted">No Report</span>
                      ) : h.state === "FAILED" ? (
                        <span title={h.lastAttemptError ?? "Collection failed"}><StatusBadge value="FAILED" /></span>
                      ) : (
                        <div className="flex items-center gap-1.5">
                          {h.overallBand && <StatusBadge value={h.overallBand} />}
                          <span className="font-mono text-xs font-bold text-ink">{h.overallScore}</span>
                          {h.lastAttemptFailedAt && (
                            <span
                              className="rounded-full border border-warn/30 bg-warn/10 p-1 text-warn"
                              title={`Latest check failed ${shortTime(h.lastAttemptFailedAt)}: ${h.lastAttemptError ?? "Unknown error"}`}
                            >
                              <AlertTriangle size={10} />
                            </span>
                          )}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3"><ScoreBar value={h.cpuScore} /></td>
                    <td className="px-4 py-3"><ScoreBar value={h.memoryScore} /></td>
                    <td className="px-4 py-3"><ScoreBar value={h.storageScore} /></td>
                    <td className="px-4 py-3"><ScoreBar value={h.batteryScore} /></td>
                    <td className="px-4 py-3">
                      {h.recommendationCount > 0 ? (
                        <span className="rounded-full bg-warn/15 px-2 py-0.5 text-[10px] font-bold text-warn">
                          {h.recommendationCount} action{h.recommendationCount > 1 ? "s" : ""}
                        </span>
                      ) : (
                        <span className="text-[11px] text-muted">None</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-muted">
                      {h.collectedAt ? shortTime(h.collectedAt) : "—"}
                      {h.lastAttemptFailedAt && (
                        <div className="text-[10px] text-warn">check failed {shortTime(h.lastAttemptFailedAt)}</div>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      {h.recommendationCount > 0 ? (
                        <button
                          onClick={() => toggleInspect(h.endpointId)}
                          className="inline-flex items-center gap-1 rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted transition hover:text-ink"
                        >
                          <span>{isExpanded ? "Hide" : "Inspect"}</span>
                          {isExpanded ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                        </button>
                      ) : (
                        <Link
                          href={`/endpoints/${h.endpointId}`}
                          className="rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted transition hover:text-accent"
                        >
                          Details
                        </Link>
                      )}
                    </td>
                  </tr>,
                ];
                if (isExpanded) {
                  rows.push(
                    <tr key={`${h.endpointId}-recs`}>
                      <td colSpan={9} className="bg-panel2/40 p-4">
                        <div className="mb-2 text-xs font-bold text-ink">
                          Maintenance Recommendations for {h.hostname || h.macAddress}:
                        </div>
                        {!recos[h.endpointId] ? (
                          <div className="text-xs text-muted">Loading…</div>
                        ) : (
                          <div className="space-y-1.5">
                            {recos[h.endpointId].map((rec, i) => (
                              <div key={i} className="flex items-start gap-2 rounded-lg border border-border/80 bg-panel p-2.5 text-xs">
                                <span
                                  className={`rounded px-1.5 py-0.5 text-[10px] font-bold ${
                                    rec.priority === "HIGH" ? "bg-bad/15 text-bad"
                                      : rec.priority === "MEDIUM" ? "bg-warn/15 text-warn"
                                      : "bg-good/15 text-good"
                                  }`}
                                >
                                  {rec.priority}
                                </span>
                                <div>
                                  <span className="font-semibold text-ink">{rec.area}:</span>{" "}
                                  <span className="text-muted">{rec.action}</span>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                }
                return rows;
              })}
            </tbody>
          </table>
        </div>

        {items !== null && (
          <PaginationBar
            page={page}
            pageCount={pageCount}
            pageSize={pageSize}
            total={total}
            from={from}
            to={to}
            setPage={(p) => setPage(Math.max(1, Math.min(p, pageCount)))}
            setPageSize={(n) => { setPageSize(n); setPage(1); }}
          />
        )}
      </div>
    </div>
  );
}