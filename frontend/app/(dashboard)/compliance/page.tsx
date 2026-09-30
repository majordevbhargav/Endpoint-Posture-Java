"use client";

import { useEffect, useMemo, useState } from "react";
import { usePolling } from "@/lib/usePolling";
import Link from "next/link";
import {
  ShieldCheck,
  ShieldAlert,
  Shield,
  RefreshCw,
  Play,
  Monitor,
  CheckCircle2,
  Search,
  ChevronDown,
  ChevronUp,
} from "lucide-react";
import { api, EndpointResponse, AssessmentResponse } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ConnectionDot } from "@/components/ui/ConnectionDot";
import { can, DENIED_HINT } from "@/lib/permissions";

type Row = {
  e: EndpointResponse;
  a: AssessmentResponse | null;
};

export default function CompliancePage() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState("");
  const [filterResult, setFilterResult] = useState<string>("ALL");
  const [connFilter, setConnFilter] = useState<"ALL" | "CONNECTED" | "DISCONNECTED">("ALL");
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  // UI convenience only: the backend @PreAuthorize rule is the real control.
  const mayEnqueue = can("enqueue");

  const loadData = async () => {
    setLoading(true);
    try {
      const [eps, latest] = await Promise.all([api.listEndpoints(), api.latestPostureAll()]);
      const byEndpoint = new Map(latest.map((a) => [a.endpointId, a]));
      setRows(eps.map((e) => ({ e, a: byEndpoint.get(e.id) ?? null })));
    } catch {
      setRows((prev) => prev ?? []); // keep the last good data on a failed poll
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  usePolling(loadData, 20000);

  // The pass-rate cards describe the live (connected) fleet only, matching the
  // Command Center. The table below still lists every device.
  const connectedRows = useMemo(() => (rows ?? []).filter((r) => r.e.connected), [rows]);

  const checkTypes = useMemo(() => {
    const defaultTypes = ["FIREWALL", "OPEN_PORTS", "APPLICATIONS"];
    const found = [
      ...new Set((rows ?? []).flatMap((r) => r.a?.checks.map((c) => c.checkType) ?? [])),
    ];
    return Array.from(new Set([...defaultTypes, ...found]));
  }, [rows]);

  const passStats = (type: string) => {
    const list = connectedRows
      .map((r) => r.a?.checks.find((c) => c.checkType === type))
      .filter(Boolean);
    if (list.length === 0) return { rate: null as number | null, n: 0 };
    const passing = list.filter((c) => c!.status === "COMPLIANT").length;
    return { rate: Math.round((passing / list.length) * 100), n: list.length };
  };

  const overall = useMemo(() => {
    const list = connectedRows.map((r) => r.a?.status).filter(Boolean);
    if (list.length === 0) return { rate: null as number | null, n: 0 };
    const passing = list.filter((s) => s === "COMPLIANT").length;
    return { rate: Math.round((passing / list.length) * 100), n: list.length };
  }, [connectedRows]);

  const firewall = passStats("FIREWALL");
  const ports = passStats("OPEN_PORTS");
  const apps = passStats("APPLICATIONS");

  const fmt = (rate: number | null) => (rate != null ? `${rate}%` : "—");
  const basis = (n: number) => `${n} connected device${n === 1 ? "" : "s"} assessed`;

  async function triggerScanAll() {
    if (!mayEnqueue) return;
    const targets = (rows ?? []).filter((r) => r.e.connected);
    if (targets.length === 0) {
      setActionNotice("No connected devices to scan right now.");
      setTimeout(() => setActionNotice(null), 4000);
      return;
    }
    setActionNotice("Enqueuing posture checks for connected endpoints…");
    let count = 0;
    for (const { e } of targets) {
      try {
        await api.enqueueJob(e.id, "POSTURE_CHECK");
        count++;
      } catch {
        // continue
      }
    }
    setActionNotice(`Enqueued ${count} posture check jobs. Follow progress in Assessment Queue.`);
    setTimeout(() => setActionNotice(null), 5000);
  }

  const filteredRows = useMemo(() => {
    if (!rows) return [];
    return rows.filter(({ e, a }) => {
      if (connFilter === "CONNECTED" && !e.connected) return false;
      if (connFilter === "DISCONNECTED" && e.connected) return false;

      if (filterResult !== "ALL") {
        if (filterResult === "COMPLIANT" && a?.status !== "COMPLIANT") return false;
        if (filterResult === "NON_COMPLIANT" && a?.status !== "NON_COMPLIANT") return false;
        if (filterResult === "ERROR" && a?.status !== "ERROR") return false;
        if (filterResult === "UNASSESSED" && a !== null) return false;
      }

      if (search.trim()) {
        const q = search.toLowerCase();
        const matchesName = e.hostname?.toLowerCase().includes(q);
        const matchesMac = e.macAddress.toLowerCase().includes(q);
        const matchesIp = e.ipAddress?.toLowerCase().includes(q);
        if (!matchesName && !matchesMac && !matchesIp) return false;
      }

      return true;
    });
  }, [rows, filterResult, connFilter, search]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Compliance Matrix</h1>
          <p className="mt-1 text-xs text-muted">
            Continuous posture assessment results evaluated against enterprise baseline policies. Pass rates cover
            connected devices; the table lists every device with its last known result.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={loadData}
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

      {/* Compliance Pass Rates (connected devices only) */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Overall Fleet Pass Rate</span>
            <ShieldCheck size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{fmt(overall.rate)}</div>
          <div className="text-[11px] text-muted">{basis(overall.n)}</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Windows Firewall</span>
            <Shield size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{fmt(firewall.rate)}</div>
          <div className="text-[11px] text-muted">Domain / Private / Public · {basis(firewall.n)}</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Port Reachability Policy</span>
            <CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{fmt(ports.rate)}</div>
          <div className="text-[11px] text-muted">Active network reachability · {basis(ports.n)}</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Application Control</span>
            <ShieldAlert size={16} className="text-warn" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{fmt(apps.rate)}</div>
          <div className="text-[11px] text-muted">Required & blocked software · {basis(apps.n)}</div>
        </div>
      </div>

      {/* Filter Bar */}
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
            onChange={(e) => setConnFilter(e.target.value as "ALL" | "CONNECTED" | "DISCONNECTED")}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Connections</option>
            <option value="CONNECTED">Connected on ISE</option>
            <option value="DISCONNECTED">Disconnected</option>
          </select>

          <select
            value={filterResult}
            onChange={(e) => setFilterResult(e.target.value)}
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

      {/* Matrix Table */}
      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="px-4 py-3">Device</th>
                <th className="px-4 py-3">ISE Connection</th>
                <th className="px-4 py-3">Overall Posture</th>
                {checkTypes.map((t) => (
                  <th key={t} className="px-4 py-3">
                    {t === "FIREWALL"
                      ? "Firewall"
                      : t === "OPEN_PORTS"
                      ? "Listening Ports"
                      : t === "APPLICATIONS"
                      ? "Application Control"
                      : t}
                  </th>
                ))}
                <th className="px-4 py-3 text-right">Details</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={4 + checkTypes.length} className="py-12 text-center text-muted">
                    Loading compliance matrix…
                  </td>
                </tr>
              )}
              {rows !== null && filteredRows.length === 0 && (
                <tr>
                  <td colSpan={4 + checkTypes.length} className="py-12 text-center text-muted">
                    No matching endpoints found in compliance database.
                  </td>
                </tr>
              )}
              {filteredRows.map(({ e, a }) => {
                const isExpanded = expandedId === e.id;
                return (
                  <tr key={e.id} className={`transition hover:bg-ink/[0.02] ${!e.connected ? "opacity-75" : ""}`}>
                    <td className="px-4 py-3">
                      <Link
                        href={`/endpoints/${e.id}`}
                        className="flex items-center gap-1.5 font-mono font-medium text-accent hover:underline"
                      >
                        <Monitor size={13} className="text-muted" />
                        <span>{e.hostname ?? e.macAddress}</span>
                      </Link>
                      {e.hostname && <div className="font-mono text-[10px] text-muted">{e.macAddress}</div>}
                    </td>

                    <td className="px-4 py-3">
                      <ConnectionDot connected={e.connected} />
                    </td>

                    <td className="px-4 py-3">
                      {a ? (
                        <StatusBadge value={a.status} />
                      ) : (
                        <span className="text-[11px] text-muted">Unassessed</span>
                      )}
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
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {/* Expanded Row Detail Drawer */}
        {expandedId && (
          <div className="border-t border-border bg-panel2/30 p-5">
            {(() => {
              const item = rows?.find((r) => r.e.id === expandedId);
              if (!item || !item.a) {
                return (
                  <div className="text-xs text-muted">No completed assessment found for this endpoint.</div>
                );
              }
              return (
                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="text-xs font-bold text-ink">
                      Detailed Assessment Breakdown: {item.e.hostname || item.e.macAddress}
                    </div>
                    <Link href={`/endpoints/${item.e.id}`} className="text-xs text-accent hover:underline">
                      Open Full 360° View &rarr;
                    </Link>
                  </div>

                  <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                    {item.a.checks.map((c) => (
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
              );
            })()}
          </div>
        )}
      </div>
    </div>
  );
}