"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  ShieldAlert,
  ShieldX,
  RefreshCw,
  Search,
  ExternalLink,
  Info,
  User,
  Monitor,
} from "lucide-react";
import { api, EndpointResponse, IseActionState, IseStatus } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { usePolling } from "@/lib/usePolling";

interface EnforcementRow {
  endpointId: string;
  hostname: string;
  macAddress: string;
  ipAddress: string | null;
  state: "Restricted" | "Re-auth requested" | "Clear requested" | "Last attempt failed" | "Clear failed";
  lastAction: string;
  succeeded: boolean;
  operator: string;
  detail: string;
  occurredAt: string;
}

function deriveState(actionType: string, succeeded: boolean, mode: string): EnforcementRow["state"] {
  if (!succeeded) return actionType === "CLEAR_RESTRICTION" ? "Clear failed" : "Last attempt failed";
  if (actionType === "RESTRICT") return mode === "ANC" ? "Restricted" : "Re-auth requested";
  if (actionType === "CLEAR_RESTRICTION") return "Clear requested";
  return "Last attempt failed";
}

const columns: Column<EnforcementRow>[] = [
  {
    key: "endpoint",
    header: "Endpoint",
    className: "font-medium text-ink",
    render: (r) => (
      <Link
        href={`/endpoints/${r.endpointId}`}
        className="group flex flex-col transition hover:text-accent"
      >
        <span className="font-semibold text-ink group-hover:text-accent">{r.hostname}</span>
        <span className="font-mono text-[11px] text-muted">{r.macAddress}</span>
      </Link>
    ),
    csv: (r) => `${r.hostname} (${r.macAddress})`,
  },
  {
    key: "state",
    header: "Enforcement State",
    render: (r) => <StatusBadge value={r.state} />,
    csv: (r) => r.state,
  },
  {
    key: "lastAction",
    header: "Last Action",
    className: "font-mono text-xs text-ink",
    render: (r) => (
      <span className="flex items-center gap-1.5">
        {r.lastAction === "RESTRICT" ? (
          <ShieldX size={14} className="text-bad" />
        ) : (
          <ShieldAlert size={14} className="text-warn" />
        )}
        <span>{r.lastAction}</span>
      </span>
    ),
    csv: (r) => r.lastAction,
  },
  {
    key: "operator",
    header: "Operator",
    className: "text-ink",
    render: (r) => (
      <span className="flex items-center gap-1.5 text-xs">
        <User size={12} className="text-muted" />
        <span>{r.operator}</span>
      </span>
    ),
    csv: (r) => r.operator,
  },
  {
    key: "detail",
    header: "Detail",
    className: "max-w-xs truncate text-xs text-muted",
    render: (r) => <span title={r.detail}>{r.detail || "—"}</span>,
    csv: (r) => r.detail,
  },
  {
    key: "occurredAt",
    header: "Timestamp",
    className: "text-xs text-muted",
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
        <span>View</span>
        <ExternalLink size={10} />
      </Link>
    ),
  },
];

export default function IseActionsPage() {
  const [states, setStates] = useState<IseActionState[] | null>(null);
  const [endpoints, setEndpoints] = useState<EndpointResponse[]>([]);
  const [iseStatus, setIseStatus] = useState<IseStatus | null>(null);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);

  const load = async () => {
    try {
      const [st, epList, stIse] = await Promise.all([
        api.iseActionsState(),
        api.listEndpoints().catch(() => [] as EndpointResponse[]),
        api.iseStatus().catch(() => null),
      ]);
      setStates(st);
      setEndpoints(epList);
      setIseStatus(stIse);
    } catch {
      // API error handled by null check
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  usePolling(load, 15000);

  const epMap = useMemo(() => {
    const map = new Map<string, EndpointResponse>();
    endpoints.forEach((ep) => map.set(ep.id, ep));
    return map;
  }, [endpoints]);

  const enforcementMode = iseStatus?.enforcementMode ?? "ATTRIBUTE";

  const rows: EnforcementRow[] = useMemo(() => {
    if (!states) return [];
    return states.map((s) => {
      const ep = epMap.get(s.endpointId);
      return {
        endpointId: s.endpointId,
        hostname: ep?.hostname || `Endpoint ${s.endpointId.substring(0, 8)}`,
        macAddress: ep?.macAddress || "Unknown MAC",
        ipAddress: ep?.ipAddress ?? null,
        state: deriveState(s.actionType, s.succeeded, enforcementMode),
        lastAction: s.actionType,
        succeeded: s.succeeded,
        operator: s.operator || "System",
        detail: s.detail || "",
        occurredAt: s.occurredAt,
      };
    });
  }, [states, epMap, enforcementMode]);
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter(
      (r) =>
        r.hostname.toLowerCase().includes(q) ||
        r.macAddress.toLowerCase().includes(q) ||
        r.operator.toLowerCase().includes(q) ||
        r.state.toLowerCase().includes(q) ||
        r.lastAction.toLowerCase().includes(q)
    );
  }, [rows, search]);

  const counts = useMemo(() => {
    let restricted = 0;
    let clearRequested = 0;
    let failed = 0;
    rows.forEach((r) => {
      if (r.state === "Restricted" || r.state === "Re-auth requested") restricted++;
      else if (r.state === "Clear requested") clearRequested++;
      else if (r.state === "Last attempt failed" || r.state === "Clear failed") failed++;

    });
    return { total: rows.length, restricted, clearRequested, failed };
  }, [rows]);



  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">ISE Enforcement State</h1>
          <p className="mt-1 text-xs text-muted">
            Fleet-wide restriction state derived from the latest RESTRICT or CLEAR_RESTRICTION audit records.
          </p>
        </div>

        <button
          onClick={() => {
            setLoading(true);
            load();
          }}
          disabled={loading}
          className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-semibold text-ink transition hover:border-accent/40 hover:text-accent disabled:opacity-50"
        >
          <RefreshCw size={12} className={loading ? "animate-spin text-accent" : ""} />
          <span>Refresh</span>
        </button>
      </div>

      {/* Attribute Mode Notice */}
      {enforcementMode === "ATTRIBUTE" && (
        <div className="rounded-xl border border-accent/30 bg-accent/10 p-4 text-xs">
          <div className="flex items-center gap-2 font-semibold text-accent">
            <Info size={15} />
            <span>Enforcement Mode: ATTRIBUTE</span>
          </div>
          <p className="mt-1 text-muted">
            Under <strong>ATTRIBUTE</strong> mode, clearing a restriction does not purge custom attributes from Cisco ISE.
            The clear action is informational and requires the operator to re-share posture to update endpoint attributes.
          </p>
        </div>
      )}

      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <div className="panel p-4">
          <div className="text-xs text-muted">Managed Devices</div>
          <div className="mt-1 text-2xl font-bold text-ink">{counts.total}</div>
        </div>
        <div className="panel p-4">
          <div className="text-xs text-bad">{enforcementMode === "ANC" ? "Restricted" : "Re-auth requested"}</div>
          <div className="mt-1 text-2xl font-bold text-bad">{counts.restricted}</div>
        </div>
        <div className="panel p-4">
          <div className="text-xs text-warn">Clear Requested</div>
          <div className="mt-1 text-2xl font-bold text-warn">{counts.clearRequested}</div>
        </div>
        <div className="panel p-4">
          <div className="text-xs text-bad">Last Attempt Failed</div>
          <div className="mt-1 text-2xl font-bold text-bad">{counts.failed}</div>
        </div>
      </div>

      {/* Table Panel */}
      <div className="panel overflow-hidden">
        <div className="border-b border-border/60 p-4">
          <div className="relative max-w-sm">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <input
              type="text"
              placeholder="Search by hostname, MAC, state, or operator…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full rounded-lg border border-border bg-base/50 py-1.5 pl-8 pr-3 text-xs text-ink placeholder:text-muted focus:border-accent focus:outline-none"
            />
          </div>
        </div>

        <DataTable
          rows={states === null ? null : filtered}
          columns={columns}
          rowKey={(r) => r.endpointId}
          csvFilename="ise_actions_state"
          emptyMessage={
            search ? "No endpoints match your filter." : "No restriction or clear actions recorded across the fleet yet."
          }
          loadingMessage="Loading ISE action states…"
          defaultPageSize={20}
        />
      </div>
    </div>
  );
}
