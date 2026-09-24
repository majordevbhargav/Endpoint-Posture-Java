"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  Network,
  Search,
  RefreshCw,
  ShieldCheck,
  ShieldAlert,
  Radio,
  Monitor,
  ExternalLink,
  CheckCircle2,
  XCircle,
} from "lucide-react";
import { PortRow, listPorts } from "@/lib/inventory";
import { StatusBadge } from "@/components/ui/StatusBadge";

export default function PortsPage() {
  const [rows, setRows] = useState<PortRow[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [q, setQ] = useState("");
  const [reachFilter, setReachFilter] = useState<"ALL" | "REACHABLE" | "BLOCKED">("ALL");

  const loadData = async () => {
    setLoading(true);
    try {
      const data = await listPorts();
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
    const reachable = list.filter((r) => r.reachable === true).length;
    const blocked = list.filter((r) => r.reachable === false).length;
    const uniqueDevices = new Set(list.map((r) => r.macAddress)).size;
    return { total, reachable, blocked, uniqueDevices };
  }, [rows]);

  const shown = useMemo(() => {
    if (!rows) return [];
    return rows.filter((r) => {
      if (reachFilter === "REACHABLE" && r.reachable !== true) return false;
      if (reachFilter === "BLOCKED" && r.reachable !== false) return false;

      if (q.trim()) {
        const query = q.toLowerCase();
        const matchesPort = String(r.port).includes(query);
        const matchesProc = r.process?.toLowerCase().includes(query);
        const matchesHost = r.hostname?.toLowerCase().includes(query);
        const matchesMac = r.macAddress.toLowerCase().includes(query);
        if (!matchesPort && !matchesProc && !matchesHost && !matchesMac) return false;
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
        <div className="relative flex-1 max-w-md">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Filter by port number, service, or device…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none focus:border-accent"
          />
        </div>

        <div className="flex items-center gap-2">
          <select
            value={reachFilter}
            onChange={(e) => setReachFilter(e.target.value as any)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Reachabilities</option>
            <option value="REACHABLE">Reachable / Open Only</option>
            <option value="BLOCKED">Blocked / Filtered Only</option>
          </select>
        </div>
      </div>

      {/* Ports Table */}
      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[700px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="py-3 px-4">Port Number</th>
                <th className="py-3 px-4">Service / Process</th>
                <th className="py-3 px-4">Reachability Verdict</th>
                <th className="py-3 px-4">Device Hostname</th>
                <th className="py-3 px-4">MAC Address</th>
                <th className="py-3 px-4 text-right">Endpoint</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={6} className="py-12 text-center text-muted">
                    Loading port inventory from posture checks…
                  </td>
                </tr>
              )}
              {rows !== null && shown.length === 0 && (
                <tr>
                  <td colSpan={6} className="py-12 text-center text-muted">
                    No matching listening ports recorded yet. Ports are populated during posture agent runs.
                  </td>
                </tr>
              )}
              {shown.map((r, i) => (
                <tr key={i} className="transition hover:bg-ink/[0.02]">
                  <td className="py-3 px-4 font-mono font-bold text-ink">
                    <span className="rounded bg-base px-2 py-1 text-xs border border-border">
                      TCP/{r.port}
                    </span>
                  </td>

                  <td className="py-3 px-4 text-ink font-medium">
                    {r.process || "Active Listener"}
                  </td>

                  <td className="py-3 px-4">
                    {r.reachable == null ? (
                      <span className="text-muted text-[11px]">Untested</span>
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
                    )}
                  </td>

                  <td className="py-3 px-4 text-ink">
                    {r.hostname || "Windows Host"}
                  </td>

                  <td className="py-3 px-4 font-mono text-muted">
                    {r.macAddress}
                  </td>

                  <td className="py-3 px-4 text-right">
                    <Link
                      href="/endpoints"
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
