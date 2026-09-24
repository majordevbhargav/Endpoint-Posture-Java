"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  History,
  ShieldCheck,
  ShieldAlert,
  ShieldX,
  Search,
  RefreshCw,
  CheckCircle2,
  XCircle,
  ExternalLink,
  User,
} from "lucide-react";
import { api, IseActionAudit } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";

export default function AuditPage() {
  const [rows, setRows] = useState<IseActionAudit[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [q, setQ] = useState("");
  const [actionFilter, setActionFilter] = useState<string>("ALL");
  const [resultFilter, setResultFilter] = useState<string>("ALL");

  const loadData = async () => {
    setLoading(true);
    try {
      const data = await api.auditActions();
      setRows(data);
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const stats = useMemo(() => {
    const list = rows ?? [];
    const total = list.length;
    const shareCount = list.filter((r) => r.actionType === "SHARE_POSTURE").length;
    const restrictCount = list.filter((r) => r.actionType === "RESTRICT").length;
    const clearCount = list.filter((r) => r.actionType === "CLEAR_RESTRICTION").length;
    const successCount = list.filter((r) => r.succeeded).length;
    const successRate = total === 0 ? 100 : Math.round((successCount / total) * 100);
    return { total, shareCount, restrictCount, clearCount, successRate };
  }, [rows]);

  const shown = useMemo(() => {
    if (!rows) return [];
    return rows.filter((r) => {
      if (actionFilter !== "ALL" && r.actionType !== actionFilter) return false;
      if (resultFilter === "SUCCESS" && !r.succeeded) return false;
      if (resultFilter === "FAILED" && r.succeeded) return false;

      if (q.trim()) {
        const query = q.toLowerCase();
        const matchesAction = r.actionType.toLowerCase().includes(query);
        const matchesOp = r.operator?.toLowerCase().includes(query);
        const matchesDetail = r.detail?.toLowerCase().includes(query);
        const matchesEp = r.endpointId?.toLowerCase().includes(query);
        if (!matchesAction && !matchesOp && !matchesDetail && !matchesEp) return false;
      }
      return true;
    });
  }, [rows, actionFilter, resultFilter, q]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Cisco ISE Action Audit Log</h1>
          <p className="mt-1 text-xs text-muted">
            Unconditional append-only audit trail of every Share Posture, Restrict, and Clearance operation.
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
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Total Logged Actions</span>
            <History size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.total}</div>
          <div className="text-[11px] text-muted">ISE API operations</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Posture Shared</span>
            <ShieldCheck size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{stats.shareCount}</div>
          <div className="text-[11px] text-muted">Transmitted to ISE</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Quarantine Actions</span>
            <ShieldX size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{stats.restrictCount}</div>
          <div className="text-[11px] text-muted">ANC restrictions applied</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Success Rate</span>
            <CheckCircle2 size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.successRate}%</div>
          <div className="text-[11px] text-muted">Operational reliability</div>
        </div>
      </div>

      {/* Filter and Search Bar */}
      <div className="panel flex flex-col justify-between gap-3 p-3 sm:flex-row sm:items-center">
        <div className="relative flex-1 max-w-md">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search by action, operator, or details…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none focus:border-accent"
          />
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <select
            value={actionFilter}
            onChange={(e) => setActionFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Action Types</option>
            <option value="SHARE_POSTURE">Share Posture</option>
            <option value="RESTRICT">Restrict (ANC)</option>
            <option value="CLEAR_RESTRICTION">Clear Restriction</option>
          </select>

          <select
            value={resultFilter}
            onChange={(e) => setResultFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Results</option>
            <option value="SUCCESS">Succeeded Only</option>
            <option value="FAILED">Failed Only</option>
          </select>
        </div>
      </div>

      {/* Audit Table */}
      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="py-3 px-4">Action Type</th>
                <th className="py-3 px-4">Outcome</th>
                <th className="py-3 px-4">Operator</th>
                <th className="py-3 px-4">Target Endpoint</th>
                <th className="py-3 px-4">Execution Detail</th>
                <th className="py-3 px-4">Timestamp</th>
                <th className="py-3 px-4 text-right">Inspect</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    Loading ISE audit log…
                  </td>
                </tr>
              )}
              {rows !== null && shown.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    No matching audit records found. Actions appear here unconditionally as Share Posture or Restrict commands are executed.
                  </td>
                </tr>
              )}
              {shown.map((r) => (
                <tr key={r.id} className="transition hover:bg-ink/[0.02]">
                  <td className="py-3 px-4 font-semibold text-ink">
                    <span className="flex items-center gap-1.5">
                      {r.actionType === "SHARE_POSTURE" && <ShieldCheck size={14} className="text-accent" />}
                      {r.actionType === "RESTRICT" && <ShieldX size={14} className="text-bad" />}
                      {r.actionType === "CLEAR_RESTRICTION" && <ShieldAlert size={14} className="text-good" />}
                      <span>{r.actionType}</span>
                    </span>
                  </td>

                  <td className="py-3 px-4">
                    <StatusBadge value={r.succeeded ? "COMPLIANT" : "ERROR"} />
                  </td>

                  <td className="py-3 px-4 text-ink flex items-center gap-1.5">
                    <User size={12} className="text-muted" />
                    <span>{r.operator || "System"}</span>
                  </td>

                  <td className="py-3 px-4 font-mono text-muted">
                    <Link
                      href={`/endpoints/${r.endpointId}`}
                      className="text-accent hover:underline"
                    >
                      {r.endpointId ? r.endpointId.substring(0, 8) + "…" : "—"}
                    </Link>
                  </td>

                  <td className="py-3 px-4 max-w-sm truncate text-muted">
                    {r.detail}
                  </td>

                  <td className="py-3 px-4 text-muted">
                    {new Date(r.occurredAt).toLocaleString([], {
                      month: "short",
                      day: "numeric",
                      hour: "2-digit",
                      minute: "2-digit",
                      second: "2-digit",
                    })}
                  </td>

                  <td className="py-3 px-4 text-right">
                    <Link
                      href={`/endpoints/${r.endpointId}`}
                      className="inline-flex items-center gap-1 text-[11px] text-accent hover:underline"
                    >
                      <span>Device</span>
                      <ExternalLink size={10} />
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}