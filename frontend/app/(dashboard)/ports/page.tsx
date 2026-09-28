"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  Network,
  Search,
  RefreshCw,
  Monitor,
  ExternalLink,
  CheckCircle2,
  XCircle,
} from "lucide-react";
import { PortRow, listPorts } from "@/lib/inventory";
import { Column, DataTable } from "@/components/ui/DataTable";

// The API rows have no unique id, so one is added on load (DataTable needs a stable row key).
type KeyedPort = PortRow & { rowId: string };

const columns: Column<KeyedPort>[] = [
  {
    key: "port",
    header: "Port Number",
    render: (r) => (
      <span className="rounded border border-border bg-base px-2 py-1 font-mono text-xs font-bold text-ink">
        TCP/{r.port}
      </span>
    ),
    csv: (r) => r.port,
  },
  {
    key: "process",
    header: "Service / Process",
    className: "font-medium text-ink",
    render: (r) => r.process || "Active Listener",
    csv: (r) => r.process,
  },
  {
    key: "pid",
    header: "PID",
    className: "font-mono text-muted",
    render: (r) => (r.pid != null ? String(r.pid) : "—"),
    csv: (r) => r.pid,
  },
  {
    key: "reachable",
    header: "Reachability Verdict",
    render: (r) =>
      r.reachable == null ? (
        <span className="text-[11px] text-muted">Untested</span>
      ) : r.reachable ? (
        <span className="inline-flex items-center gap-1.5 rounded-full border border-good/25 bg-good/10 px-2.5 py-0.5 text-xs font-medium text-good">
          <span className="h-1.5 w-1.5 rounded-full bg-good" />
          Reachable (Probe Connected)
        </span>
      ) : (
        <span className="inline-flex items-center gap-1.5 rounded-full border border-bad/25 bg-bad/10 px-2.5 py-0.5 text-xs font-medium text-bad">
          <span className="h-1.5 w-1.5 rounded-full bg-bad" />
          Blocked / Filtered
        </span>
      ),
    csv: (r) => (r.reachable == null ? "UNTESTED" : r.reachable ? "REACHABLE" : "BLOCKED"),
  },
  {
    key: "hostname",
    header: "Device Hostname",
    className: "text-ink",
    render: (r) => r.hostname || "Windows Host",
    csv: (r) => r.hostname,
  },
  {
    key: "macAddress",
    header: "MAC Address",
    className: "font-mono text-muted",
  },
  {
    key: "view",
    header: "Endpoint",
    headerClassName: "text-right",
    className: "text-right",
    exportable: false,
    render: () => (
      <Link
        href="/endpoints"
        className="inline-flex items-center gap-1 text-[11px] text-accent hover:underline"
      >
        <span>Device</span>
        <ExternalLink size={10} />
      </Link>
    ),
  },
];

export default function PortsPage() {
  const [rows, setRows] = useState<KeyedPort[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [reachFilter, setReachFilter] = useState<"ALL" | "REACHABLE" | "BLOCKED">("ALL");

  const loadData = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listPorts();
      setRows(data.map((r, i) => ({ ...r, rowId: `${r.macAddress}-${r.port}-${i}` })));
    } catch {
      setRows([]);
      setError("Could not load inventory from the backend.");
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
    const reachable = list.filter((r) => r.reachable === true).length;
    const blocked = list.filter((r) => r.reachable === false).length;
    const uniqueDevices = new Set(list.map((r) => r.macAddress)).size;
    return { total, reachable, blocked, uniqueDevices };
  }, [rows]);

  const shown = useMemo(() => {
    if (!rows) return null;
    return rows.filter((r) => {
      if (reachFilter === "REACHABLE" && r.reachable !== true) return false;
      if (reachFilter === "BLOCKED" && r.reachable !== false) return false;

      if (q.trim()) {
        const query = q.toLowerCase();
        const matches =
          String(r.port).includes(query) ||
          r.process?.toLowerCase().includes(query) ||
          r.hostname?.toLowerCase().includes(query) ||
          r.macAddress.toLowerCase().includes(query);
        if (!matches) return false;
      }
      return true;
    });
  }, [rows, reachFilter, q]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Listening Ports & Reachability</h1>
          <p className="mt-1 text-xs text-muted">
            Network listeners identified and probed for active reachability during endpoint posture checks.
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

      {error && (
        <div className="rounded-lg border border-bad/30 bg-bad/10 p-3 text-xs text-bad">{error}</div>
      )}

      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Total Evaluated Ports</span>
            <Network size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.total}</div>
          <div className="text-[11px] text-muted">Across all endpoints</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Reachable / Open</span>
            <CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{stats.reachable}</div>
          <div className="text-[11px] text-muted">Probes connected successfully</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Blocked / Filtered</span>
            <XCircle size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{stats.blocked}</div>
          <div className="text-[11px] text-muted">Firewall or probe blocked</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Monitored Endpoints</span>
            <Monitor size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.uniqueDevices}</div>
          <div className="text-[11px] text-muted">Devices with port scans</div>
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
            placeholder="Filter by port number, service, or device…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
          />
        </div>

        <div className="flex items-center gap-2">
          <select
            value={reachFilter}
            onChange={(e) => setReachFilter(e.target.value as "ALL" | "REACHABLE" | "BLOCKED")}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Reachabilities</option>
            <option value="REACHABLE">Reachable / Open Only</option>
            <option value="BLOCKED">Blocked / Filtered Only</option>
          </select>
        </div>
      </div>

      {/* Ports table (paginated + CSV) */}
      <DataTable<KeyedPort>
        rows={shown}
        columns={columns}
        rowKey={(r) => r.rowId}
        csvFilename="listening-ports"
        loadingMessage="Loading port inventory…"
        emptyMessage="No matching listening ports recorded yet. Ports are populated during posture agent runs."
        minWidth={760}
        toolbarLeft={shown ? `${shown.length} matching port${shown.length === 1 ? "" : "s"}` : undefined}
      />
    </div>
  );
}