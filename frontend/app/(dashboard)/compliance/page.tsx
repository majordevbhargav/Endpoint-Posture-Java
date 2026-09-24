"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  ShieldCheck,
  ShieldAlert,
  Shield,
  RefreshCw,
  Play,
  Monitor,
  CheckCircle2,
  XCircle,
  HelpCircle,
  Search,
  ChevronDown,
  ChevronUp,
} from "lucide-react";
import { api, EndpointResponse, AssessmentResponse } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ConnectionDot } from "@/components/ui/ConnectionDot";

type Row = {
  e: EndpointResponse;
  a: AssessmentResponse | null;
};

export default function CompliancePage() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState("");
  const [filterResult, setFilterResult] = useState<string>("ALL");
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const loadData = async () => {
    setLoading(true);
    try {
      const eps = await api.listEndpoints();
      const withPosture = await Promise.all(
        eps.map(async (e) => ({
          e,
          a: await api.latestPosture(e.id).catch(() => null),
        }))
      );
      setRows(withPosture);
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 20000);
    return () => clearInterval(interval);
  }, []);

  const checkTypes = useMemo(() => {
    const defaultTypes = ["FIREWALL", "OPEN_PORTS", "APPLICATIONS"];
    const found = [
      ...new Set(
        (rows ?? []).flatMap((r) => r.a?.checks.map((c) => c.checkType) ?? [])
      ),
    ];
    return Array.from(new Set([...defaultTypes, ...found]));
  }, [rows]);

  const passRate = (type: string) => {
    const list = (rows ?? [])
      .map((r) => r.a?.checks.find((c) => c.checkType === type))
      .filter(Boolean);
    if (list.length === 0) return null;
    const passing = list.filter((c) => c!.status === "COMPLIANT").length;
    return Math.round((passing / list.length) * 100);
  };

  const overallRate = useMemo(() => {
    const list = (rows ?? []).map((r) => r.a?.status).filter(Boolean);
    if (list.length === 0) return null;
    const passing = list.filter((s) => s === "COMPLIANT").length;
    return Math.round((passing / list.length) * 100);
  }, [rows]);

  async function triggerScanAll() {
    if (!rows || rows.length === 0) return;
    setActionNotice("Enqueuing posture checks for all endpoints…");
    let count = 0;
    for (const { e } of rows) {
      try {
        await api.enqueueJob(e.id, "POSTURE_CHECK");
        count++;
      } catch {
        // continue
      }
    }
    setActionNotice(`Enqueued ${count} posture check jobs. JobWorker will run PowerShell agent.`);
    setTimeout(() => setActionNotice(null), 5000);
  }

  const filteredRows = useMemo(() => {
    if (!rows) return [];
    return rows.filter(({ e, a }) => {
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
  }, [rows, filterResult, search]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Compliance Matrix</h1>
          <p className="mt-1 text-xs text-muted">
            Continuous posture assessment results evaluated against enterprise baseline policies.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={loadData}
            disabled={loading}
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink hover:border-accent/40 disabled:opacity-50 transition"
          >
            <RefreshCw size={13} className={loading ? "animate-spin text-accent" : "text-muted"} />
            <span>Refresh</span>
          </button>

          <button
            onClick={triggerScanAll}
            className="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-base transition hover:bg-accent/90"
          >
            <Play size={13} />
            <span>Run Fleet Scan</span>
          </button>
        </div>
      </div>

      {actionNotice && (
        <div className="rounded-lg border border-accent/30 bg-accent/10 px-4 py-2 text-xs font-medium text-accent">
          {actionNotice}
        </div>
      )}

      {/* Compliance Pass Rates */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Overall Fleet Pass Rate</span>
            <ShieldCheck size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">
            {overallRate != null ? `${overallRate}%` : "—"}
          </div>
          <div className="text-[11px] text-muted">All checks passing</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Windows Firewall</span>
            <Shield size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">
            {passRate("FIREWALL") != null ? `${passRate("FIREWALL")}%` : "—"}
          </div>
          <div className="text-[11px] text-muted">Domain / Private / Public</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Port Reachability Policy</span>
            <CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">
            {passRate("OPEN_PORTS") != null ? `${passRate("OPEN_PORTS")}%` : "—"}
          </div>
          <div className="text-[11px] text-muted">Active network reachability</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Application Control</span>
            <ShieldAlert size={16} className="text-warn" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">
            {passRate("APPLICATIONS") != null ? `${passRate("APPLICATIONS")}%` : "—"}
          </div>
          <div className="text-[11px] text-muted">Required & blocked software</div>
        </div>
      </div>

      {/* Filter Bar */}
      <div className="panel flex flex-col justify-between gap-3 p-3 sm:flex-row sm:items-center">
        <div className="relative flex-1 max-w-md">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filter devices by hostname, MAC, or IP…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none focus:border-accent"
          />
        </div>

        <div className="flex items-center gap-2">
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
                <th className="py-3 px-4">Device</th>
                <th className="py-3 px-4">ISE Connection</th>
                <th className="py-3 px-4">Overall Posture</th>
                {checkTypes.map((t) => (
                  <th key={t} className="py-3 px-4">
                    {t === "FIREWALL"
                      ? "Firewall"
                      : t === "OPEN_PORTS"
                      ? "Listening Ports"
                      : t === "APPLICATIONS"
                      ? "Application Control"
                      : t}
                  </th>
                ))}
                <th className="py-3 px-4 text-right">Details</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={5 + checkTypes.length} className="py-12 text-center text-muted">
                    Loading compliance matrix…
                  </td>
                </tr>
              )}
              {rows !== null && filteredRows.length === 0 && (
                <tr>
                  <td colSpan={5 + checkTypes.length} className="py-12 text-center text-muted">
                    No matching endpoints found in compliance database.
                  </td>
                </tr>
              )}
              {filteredRows.map(({ e, a }) => {
                const isExpanded = expandedId === e.id;
                return (
                  <tr key={e.id} className="transition hover:bg-ink/[0.02]">
                    <td className="py-3 px-4">
                      <Link
                        href={`/endpoints/${e.id}`}
                        className="font-mono font-medium text-accent hover:underline flex items-center gap-1.5"
                      >
                        <Monitor size={13} className="text-muted" />
                        <span>{e.hostname ?? e.macAddress}</span>
                      </Link>
                      {e.hostname && (
                        <div className="font-mono text-[10px] text-muted">{e.macAddress}</div>
                      )}
                    </td>

                    <td className="py-3 px-4">
                      <ConnectionDot connected={e.connected} />
                    </td>

                    <td className="py-3 px-4">
                      {a ? (
                        <StatusBadge value={a.status} />
                      ) : (
                        <span className="text-muted text-[11px]">Unassessed</span>
                      )}
                    </td>

                    {checkTypes.map((t) => {
                      const c = a?.checks.find((x) => x.checkType === t);
                      return (
                        <td key={t} className="py-3 px-4">
                          {c ? (
                            <StatusBadge value={c.status} />
                          ) : (
                            <span className="text-muted text-[11px]">—</span>
                          )}
                        </td>
                      );
                    })}

                    <td className="py-3 px-4 text-right">
                      <button
                        onClick={() => setExpandedId(isExpanded ? null : e.id)}
                        className="inline-flex items-center gap-1 rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted hover:text-ink transition"
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
                  <div className="text-xs text-muted">
                    No completed assessment found for this endpoint.
                  </div>
                );
              }
              return (
                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="text-xs font-bold text-ink">
                      Detailed Assessment Breakdown: {item.e.hostname || item.e.macAddress}
                    </div>
                    <Link
                      href={`/endpoints/${item.e.id}`}
                      className="text-xs text-accent hover:underline"
                    >
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
                        <div className="mt-2 text-xs text-muted leading-relaxed">
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
