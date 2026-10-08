"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  Boxes,
  Search,
  RefreshCw,
  ShieldCheck,
  AlertTriangle,
  Monitor,
  CheckCircle2,
  ExternalLink,
  Lock,
} from "lucide-react";
import { AppRow, listApplications } from "@/lib/inventory";
import { api, AppPolicy } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Column, DataTable } from "@/components/ui/DataTable";

// The API rows have no unique id, so one is added on load (DataTable needs a stable row key).
type KeyedApp = AppRow & { rowId: string };

const columns: Column<KeyedApp>[] = [
  {
    key: "name",
    header: "Application",
    className: "font-semibold text-ink",
    csv: (r) => r.name,
  },
  {
    key: "version",
    header: "Version",
    className: "font-mono text-muted",
    render: (r) => r.version || "—",
    csv: (r) => r.version,
  },
  {
    key: "status",
    header: "Status",
    render: (r) =>
      r.status ? (
        <StatusBadge value={r.status} />
      ) : (
        <span className="text-[11px] text-muted">Installed</span>
      ),
    csv: (r) => r.status ?? "INSTALLED",
  },
  {
    key: "publisher",
    header: "Publisher / Source",
    className: "text-muted",
    render: (r) => r.publisher || "Windows Registry",
    csv: (r) => r.publisher,
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
    key: "summary",
    header: "Evaluation Details",
    className: "max-w-xs truncate text-muted",
    render: (r) => r.summary || "—",
    csv: (r) => r.summary,
  },
  {
    key: "view",
    header: "View",
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

export default function ApplicationsPage() {
  const [rows, setRows] = useState<KeyedApp[] | null>(null);
  const [policy, setPolicy] = useState<AppPolicy | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");

  const loadData = async () => {
    setLoading(true);
    setError(null);
    try {
      const [data, activePolicy] = await Promise.all([
        listApplications(),
        api.policy().catch(() => null), // the banner is optional; the table is not
      ]);
      setRows(data.map((r, i) => ({ ...r, rowId: `${r.macAddress}-${i}` })));
      setPolicy(activePolicy);
    } catch (e) {
      setRows([]);
      // Show the server's reason (for example the fleet-size cap), not a generic message.
      setError(e instanceof Error ? e.message : "Could not load inventory from the backend.");
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
    const compliant = list.filter((r) => r.status === "COMPLIANT").length;
    const violations = list.filter((r) => r.status === "NON_COMPLIANT").length;
    const uniqueDevices = new Set(list.map((r) => r.macAddress)).size;
    return { total, compliant, violations, uniqueDevices };
  }, [rows]);

  const shown = useMemo(() => {
    if (!rows) return null;
    return rows.filter((r) => {
      if (statusFilter !== "ALL" && r.status !== statusFilter) return false;

      if (q.trim()) {
        const query = q.toLowerCase();
        const matches =
          r.name?.toLowerCase().includes(query) ||
          r.publisher?.toLowerCase().includes(query) ||
          r.hostname?.toLowerCase().includes(query) ||
          r.macAddress?.toLowerCase().includes(query) ||
          r.summary?.toLowerCase().includes(query);
        if (!matches) return false;
      }
      return true;
    });
  }, [rows, statusFilter, q]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Application Control & Software</h1>
          <p className="mt-1 text-xs text-muted">
            Installed endpoint software evaluated against enterprise allowed and blocked policy lists.
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

      {/* Policy banner (live from the active policy) */}
      <div className="panel grid grid-cols-1 gap-4 bg-panel2/50 p-4 md:grid-cols-2">
        <div className="flex items-start gap-3">
          <div className="rounded-lg bg-good/15 p-2 text-good">
            <ShieldCheck size={18} />
          </div>
          <div>
            <div className="text-xs font-bold text-ink">Mandatory Required Applications</div>
            <div className="mt-0.5 text-xs text-muted">
              Must be installed on all Windows endpoints:{" "}
              <span className="font-mono font-semibold text-good">
                {policy ? policy.requiredApps.join(", ") || "None" : "…"}
              </span>
            </div>
          </div>
        </div>

        <div className="flex items-start gap-3">
          <div className="rounded-lg bg-bad/15 p-2 text-bad">
            <Lock size={18} />
          </div>
          <div>
            <div className="text-xs font-bold text-ink">Prohibited / Blocked Applications</div>
            <div className="mt-0.5 text-xs text-muted">
              Forbidden on enterprise endpoints:{" "}
              <span className="font-mono font-semibold text-bad">
                {policy ? policy.blockedApps.join(", ") || "None" : "…"}
              </span>
            </div>
          </div>
        </div>

        {policy && (
          <div className="text-[11px] text-muted md:col-span-2">
            Policy version <span className="font-mono font-semibold text-ink">v{policy.version}</span>
            {policy.createdBy ? ` · set by ${policy.createdBy}` : ""}
            {" · "}
            <Link href="/policies" className="text-accent hover:underline">
              Manage policy
            </Link>
          </div>
        )}
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Evaluated Endpoints</span>
            <Monitor size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.uniqueDevices}</div>
          <div className="text-[11px] text-muted">With application inventory</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Required Apps Present</span>
            <CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{stats.compliant}</div>
          <div className="text-[11px] text-muted">Satisfies baseline policy</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Blocked Apps Found</span>
            <AlertTriangle size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{stats.violations}</div>
          <div className="text-[11px] text-muted">Policy violations</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Total Installed Apps</span>
            <Boxes size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.total}</div>
          <div className="text-[11px] text-muted">Across all endpoints</div>
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
            placeholder="Filter by application name, publisher, device, or MAC…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
          />
        </div>

        <div className="flex items-center gap-2">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Statuses</option>
            <option value="COMPLIANT">Required Present</option>
            <option value="NON_COMPLIANT">Blocked Found</option>
          </select>
        </div>
      </div>

      {/* Applications table (paginated + CSV) */}
      <DataTable<KeyedApp>
        rows={shown}
        columns={columns}
        rowKey={(r) => r.rowId}
        csvFilename="installed-software"
        loadingMessage="Loading application inventory…"
        emptyMessage="No matching software records found. Inventory appears once posture checks run."
        minWidth={860}
        toolbarLeft={shown ? `${shown.length} matching application${shown.length === 1 ? "" : "s"}` : undefined}
      />
    </div>
  );
}