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
  ExternalLink,
  User,
} from "lucide-react";
import { api, IseActionAudit } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Column, DataTable } from "@/components/ui/DataTable";

const columns: Column<IseActionAudit>[] = [
  {
    key: "actionType",
    header: "Action Type",
    className: "font-semibold text-ink",
    render: (r) => (
      <span className="flex items-center gap-1.5">
        {r.actionType === "SHARE_POSTURE" && <ShieldCheck size={14} className="text-accent" />}
        {r.actionType === "RESTRICT" && <ShieldX size={14} className="text-bad" />}
        {r.actionType === "CLEAR_RESTRICTION" && <ShieldAlert size={14} className="text-good" />}
        <span>{r.actionType}</span>
      </span>
    ),
    csv: (r) => r.actionType,
  },
  {
    key: "outcome",
    header: "Outcome",
    render: (r) => <StatusBadge value={r.succeeded ? "COMPLIANT" : "ERROR"} />,
    csv: (r) => (r.succeeded ? "SUCCESS" : "FAILED"),
  },
  {
    key: "operator",
    header: "Operator",
    className: "text-ink",
    render: (r) => (
      <span className="flex items-center gap-1.5">
        <User size={12} className="text-muted" />
        <span>{r.operator || "System"}</span>
      </span>
    ),
    csv: (r) => r.operator || "System",
  },
  {
    key: "endpointId",
    header: "Target Endpoint",
    className: "font-mono text-muted",
    render: (r) => (
      <Link href={`/endpoints/${r.endpointId}`} className="text-accent hover:underline" title={r.endpointId}>
        {r.endpointId ? r.endpointId.substring(0, 8) + "…" : "—"}
      </Link>
    ),
    // CSV carries the full UUID so rows can be joined back to the database.
    csv: (r) => r.endpointId,
  },
  {
    key: "detail",
    header: "Execution Detail",
    className: "max-w-sm truncate text-muted",
    render: (r) => <span title={r.detail ?? ""}>{r.detail}</span>,
    csv: (r) => r.detail,
  },
  {
    key: "occurredAt",
    header: "Timestamp",
    className: "text-muted",
    render: (r) =>
      new Date(r.occurredAt).toLocaleString([], {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
      }),
    csv: (r) => r.occurredAt,
  },
  {
    key: "inspect",
    header: "Inspect",
    headerClassName: "text-right",
    className: "text-right",
    exportable: false,
    render: (r) => (
      <Link
        href={`/endpoints/${r.endpointId}`}
        className="inline-flex items-center gap-1 text-[11px] text-accent hover:underline"
      >
        <span>Device</span>
        <ExternalLink size={10} />
      </Link>
    ),
  },
];

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
    const successCount = list.filter((r) => r.succeeded).length;
    const successRate = total === 0 ? 100 : Math.round((successCount / total) * 100);
    return { total, shareCount, restrictCount, successRate };
  }, [rows]);

  const shown = useMemo(() => {
    if (!rows) return null;
    return rows.filter((r) => {
      if (actionFilter !== "ALL" && r.actionType !== actionFilter) return false;
      if (resultFilter === "SUCCESS" && !r.succeeded) return false;
      if (resultFilter === "FAILED" && r.succeeded) return false;

      if (q.trim()) {
        const query = q.toLowerCase();
        const matches =
          r.actionType.toLowerCase().includes(query) ||
          r.operator?.toLowerCase().includes(query) ||
          r.detail?.toLowerCase().includes(query) ||
          r.endpointId?.toLowerCase().includes(query);
        if (!matches) return false;
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
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:border-accent/40 disabled:opacity-50"
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
        <div className="relative max-w-md flex-1">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search by action, operator, or details…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
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

      {/* Audit Table (paginated + CSV) */}
      <DataTable<IseActionAudit>
        rows={shown}
        columns={columns}
        rowKey={(r) => r.id}
        csvFilename="ise-action-audit"
        loadingMessage="Loading ISE audit log…"
        emptyMessage="No matching audit records found. Actions appear here unconditionally as Share Posture or Restrict commands are executed."
        minWidth={860}
        toolbarLeft={shown ? `${shown.length} matching record${shown.length === 1 ? "" : "s"}` : undefined}
      />
    </div>
  );
}