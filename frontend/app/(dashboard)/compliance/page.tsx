"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  ShieldCheck, ShieldAlert, Shield, RefreshCw, Play, Monitor, CheckCircle2, Search, ChevronDown, ChevronUp,
} from "lucide-react";
import { api, AssessmentResponse, CategoryRate, DashboardSummary, EndpointListItem } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ConnectionDot } from "@/components/ui/ConnectionDot";
import { PaginationBar } from "@/components/ui/PaginationBar";
import { usePolling } from "@/lib/usePolling";
import { can, DENIED_HINT } from "@/lib/permissions";

const CHECK_LABELS: Record<string, string> = {
  FIREWALL: "Firewall",
  OPEN_PORTS: "Listening Ports",
  APPLICATIONS: "Application Control",
};

export default function CompliancePage() {
  const [items, setItems] = useState<EndpointListItem[] | null>(null);
  const [checks, setChecks] = useState<Record<string, AssessmentResponse>>({});
  const [total, setTotal] = useState(0);
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [categories, setCategories] = useState<CategoryRate[]>([]);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState("");
  const [debounced, setDebounced] = useState("");
  const [connFilter, setConnFilter] = useState<"ALL" | "CONNECTED" | "DISCONNECTED">("ALL");
  const [filterResult, setFilterResult] = useState("ALL");
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  // UI convenience only: the backend @PreAuthorize rule is the real control.
  const mayEnqueue = can("enqueue");
  const connectedParam = connFilter === "ALL" ? undefined : connFilter === "CONNECTED";

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
      const res = await api.endpointsPage({
        page: page - 1, size: pageSize, q: debounced, connected: connectedParam, status: filterResult,
      });
      const [batch, sum, cats] = await Promise.all([
        api.latestPostureBatch(res.items.map((i) => i.id)),
        api.dashboardSummary().catch(() => null),
        api.dashboardCategories().catch(() => [] as CategoryRate[]),
      ]);
      setItems(res.items);
      setTotal(res.total);
      setSummary(sum);
      setCategories(cats);
      setChecks(Object.fromEntries(batch.map((a) => [a.endpointId, a])));
    } catch {
      setItems((prev) => prev ?? []); // keep the last good page on a failed poll
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, debounced, connectedParam, filterResult]);

  useEffect(() => {
    load();
  }, [load]);

  usePolling(load, 20000);

  const checkTypes = useMemo(() => {
    const found = Object.values(checks).flatMap((a) => a.checks.map((c) => c.checkType));
    return Array.from(new Set(["FIREWALL", "OPEN_PORTS", "APPLICATIONS", ...found]));
  }, [checks]);

  const rate = (type: string) => categories.find((c) => c.checkType === type);
  const assessed = (summary?.compliant ?? 0) + (summary?.nonCompliant ?? 0) + (summary?.error ?? 0);
  const overallRate = assessed === 0 ? null : Math.round(((summary?.compliant ?? 0) / assessed) * 100);
  const fmt = (v: number | null | undefined) => (v != null ? `${Math.round(v)}%` : "—");
  const basis = (n: number) => `${n} connected device${n === 1 ? "" : "s"} assessed`;

  async function triggerScanAll() {
    if (!mayEnqueue) return;
    setActionNotice("Queuing posture checks for connected endpoints…");
    try {
      const res = await api.bulkEnqueue("POSTURE_CHECK");
      setActionNotice(
        res.queued === 0
          ? "Nothing to queue: every connected device already has a check pending."
          : `Queued ${res.queued} posture checks. Follow progress in Assessment Queue.`
      );
      load();
    } catch (e) {
      setActionNotice(e instanceof Error ? e.message : "Could not queue the scan.");
    }
    setTimeout(() => setActionNotice(null), 5000);
  }

  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  const fw = rate("FIREWALL"), ports = rate("OPEN_PORTS"), apps = rate("APPLICATIONS");

  return (
    <div className="space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Compliance Matrix</h1>
          <p className="mt-1 text-xs text-muted">
            Continuous posture assessment results evaluated against enterprise baseline policies. Pass rates cover
            connected devices; the table lists every device with its last known result. Search and filters run on the server.
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
            onClick={triggerScanAll}
            disabled={!mayEnqueue}
            title={!mayEnqueue ? DENIED_HINT : undefined}
            className="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-base transition hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <Play size={13} />
            <span>Scan connected endpoints</span>
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
            <span>Overall Fleet Pass Rate</span><ShieldCheck size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{fmt(overallRate)}</div>
          <div className="text-[11px] text-muted">{basis(assessed)}</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Windows Firewall</span><Shield size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{fmt(fw?.passPercent)}</div>
          <div className="text-[11px] text-muted">Domain / Private / Public · {basis(fw?.total ?? 0)}</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Port Reachability Policy</span><CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{fmt(ports?.passPercent)}</div>
          <div className="text-[11px] text-muted">Active network reachability · {basis(ports?.total ?? 0)}</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Application Control</span><ShieldAlert size={16} className="text-warn" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{fmt(apps?.passPercent)}</div>
          <div className="text-[11px] text-muted">Required & blocked software · {basis(apps?.total ?? 0)}</div>
        </div>
      </div>

      <div className="panel flex flex-col justify-between gap-3 p-3 sm:flex-row sm:items-center">
        <div className="relative max-w-md flex-1">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filter devices by hostname, MAC, or IP…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <select
            value={connFilter}
            onChange={(e) => { setConnFilter(e.target.value as "ALL" | "CONNECTED" | "DISCONNECTED"); setPage(1); }}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Connections</option>
            <option value="CONNECTED">Connected on ISE</option>
            <option value="DISCONNECTED">Disconnected</option>
          </select>
          <select
            value={filterResult}
            onChange={(e) => { setFilterResult(e.target.value); setPage(1); }}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Verdicts</option>
            <option value="COMPLIANT">Compliant</option>
            <option value="NON_COMPLIANT">Non-Compliant</option>
            <option value="ERROR">Error</option>
            <option value="UNASSESSED">Unassessed</option>
          </select>
        </div>
      </div>

      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="px-4 py-3">Device</th>
                <th className="px-4 py-3">ISE Connection</th>
                <th className="px-4 py-3">Overall Posture</th>
                {checkTypes.map((t) => <th key={t} className="px-4 py-3">{CHECK_LABELS[t] ?? t}</th>)}
                <th className="px-4 py-3 text-right">Details</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {items === null && (
                <tr><td colSpan={4 + checkTypes.length} className="py-12 text-center text-muted">Loading compliance matrix…</td></tr>
              )}
              {items !== null && items.length === 0 && (
                <tr><td colSpan={4 + checkTypes.length} className="py-12 text-center text-muted">No matching endpoints found in compliance database.</td></tr>
              )}
              {(items ?? []).flatMap((e) => {
                const a = checks[e.id];
                const isExpanded = expandedId === e.id;
                const rows = [
                  <tr key={e.id} className={`transition hover:bg-ink/[0.02] ${!e.connected ? "opacity-75" : ""}`}>
                    <td className="px-4 py-3">
                      <Link href={`/endpoints/${e.id}`} className="flex items-center gap-1.5 font-mono font-medium text-accent hover:underline">
                        <Monitor size={13} className="text-muted" />
                        <span>{e.hostname ?? e.macAddress}</span>
                      </Link>
                      {e.hostname && <div className="font-mono text-[10px] text-muted">{e.macAddress}</div>}
                    </td>
                    <td className="px-4 py-3"><ConnectionDot connected={e.connected} /></td>
                    <td className="px-4 py-3">
                      {e.postureStatus ? <StatusBadge value={e.postureStatus} /> : <span className="text-[11px] text-muted">Unassessed</span>}
                    </td>
                    {checkTypes.map((t) => {
                      const c = a?.checks.find((x) => x.checkType === t);
                      return (
                        <td key={t} className="px-4 py-3">
                          {c ? <StatusBadge value={c.status} /> : <span className="text-[11px] text-muted">—</span>}
                        </td>
                      );
                    })}
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setExpandedId(isExpanded ? null : e.id)}
                        className="inline-flex items-center gap-1 rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted transition hover:text-ink"
                      >
                        <span>{isExpanded ? "Hide" : "Inspect"}</span>
                        {isExpanded ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                      </button>
                    </td>
                  </tr>,
                ];
                if (isExpanded) {
                  rows.push(
                    <tr key={`${e.id}-detail`}>
                      <td colSpan={4 + checkTypes.length} className="bg-panel2/30 p-5">
                        {!a ? (
                          <div className="text-xs text-muted">No completed assessment found for this endpoint.</div>
                        ) : (
                          <div className="space-y-3">
                            <div className="flex items-center justify-between">
                              <div className="text-xs font-bold text-ink">
                                Detailed Assessment Breakdown: {e.hostname || e.macAddress}
                              </div>
                              <Link href={`/endpoints/${e.id}`} className="text-xs text-accent hover:underline">
                                Open Full 360° View &rarr;
                              </Link>
                            </div>
                            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                              {a.checks.map((c) => (
                                <div key={c.id} className="rounded-lg border border-border bg-panel p-3">
                                  <div className="flex items-center justify-between text-xs font-semibold text-ink">
                                    <span>{c.checkType}</span>
                                    <StatusBadge value={c.status} />
                                  </div>
                                  <div className="mt-2 text-xs leading-relaxed text-muted">
                                    {c.details?.summary ? String(c.details.summary) : "Checked and evaluated."}
                                  </div>
                                </div>
                              ))}
                            </div>
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