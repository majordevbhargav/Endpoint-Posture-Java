"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  Boxes,
  Search,
  RefreshCw,
  ShieldCheck,
  ShieldAlert,
  AlertTriangle,
  Monitor,
  CheckCircle2,
  ExternalLink,
  Lock,
} from "lucide-react";
import { AppRow, listApplications } from "@/lib/inventory";
import { StatusBadge } from "@/components/ui/StatusBadge";

export default function ApplicationsPage() {
  const [rows, setRows] = useState<AppRow[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [q, setQ] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");

  const loadData = async () => {
    setLoading(true);
    try {
      const data = await listApplications();
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
    const compliant = list.filter((r) => r.status === "COMPLIANT").length;
    const violations = list.filter((r) => r.status === "NON_COMPLIANT").length;
    const uniqueDevices = new Set(list.map((r) => r.macAddress)).size;
    return { total, compliant, violations, uniqueDevices };
  }, [rows]);

  const shown = useMemo(() => {
    if (!rows) return [];
    return rows.filter((r) => {
      if (statusFilter !== "ALL" && r.status !== statusFilter) return false;

      if (q.trim()) {
        const query = q.toLowerCase();
        const matchesName = r.name?.toLowerCase().includes(query);
        const matchesPub = r.publisher?.toLowerCase().includes(query);
        const matchesHost = r.hostname?.toLowerCase().includes(query);
        const matchesMac = r.macAddress?.toLowerCase().includes(query);
        const matchesSum = r.summary?.toLowerCase().includes(query);
        if (!matchesName && !matchesPub && !matchesHost && !matchesMac && !matchesSum) return false;
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
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink hover:border-accent/40 disabled:opacity-50 transition"
          >
            <RefreshCw size={13} className={loading ? "animate-spin text-accent" : "text-muted"} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {/* Enterprise Policy Baseline Rules Banner */}
      <div className="panel grid grid-cols-1 gap-4 bg-panel2/50 p-4 md:grid-cols-2">
        <div className="flex items-start gap-3">
          <div className="rounded-lg bg-good/15 p-2 text-good">
            <ShieldCheck size={18} />
          </div>
          <div>
            <div className="text-xs font-bold text-ink">Mandatory Required Applications</div>
            <div className="mt-0.5 text-xs text-muted">
              Must be installed on all Windows endpoints: <span className="font-mono font-semibold text-good">Cisco Secure Client</span>
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
              Forbidden on enterprise endpoints: <span className="font-mono font-semibold text-bad">uTorrent, TeamViewer</span>
            </div>
          </div>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Evaluated Endpoints</span>
            <Monitor size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.uniqueDevices}</div>
          <div className="text-[11px] text-muted">With application assessments</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Software Compliant</span>
            <CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{stats.compliant}</div>
          <div className="text-[11px] text-muted">Satisfies baseline policy</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Policy Violations</span>
            <AlertTriangle size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{stats.violations}</div>
          <div className="text-[11px] text-muted">Missing required / blocked app found</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Compliance Ratio</span>
            <Boxes size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">
            {stats.total === 0 ? "—" : `${Math.round((stats.compliant / stats.total) * 100)}%`}
          </div>
          <div className="text-[11px] text-muted">Fleet software health</div>
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
            placeholder="Filter by application name, publisher, device, or MAC…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none focus:border-accent"
          />
        </div>

        <div className="flex items-center gap-2">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Statuses</option>
            <option value="COMPLIANT">Compliant Only</option>
            <option value="NON_COMPLIANT">Violations Only</option>
          </select>
        </div>
      </div>

      {/* Applications Table */}
      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="py-3 px-4">Application / Policy</th>
                <th className="py-3 px-4">Status</th>
                <th className="py-3 px-4">Publisher / Source</th>
                <th className="py-3 px-4">Device Hostname</th>
                <th className="py-3 px-4">MAC Address</th>
                <th className="py-3 px-4">Evaluation Details</th>
                <th className="py-3 px-4 text-right">View</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    Loading application control records from posture checks…
                  </td>
                </tr>
              )}
              {rows !== null && shown.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    No matching software records found. Application control results appear once posture checks run.
                  </td>
                </tr>
              )}
              {shown.map((r, i) => (
                <tr key={i} className="transition hover:bg-ink/[0.02]">
                  <td className="py-3 px-4 font-semibold text-ink">
                    {r.name}
                  </td>

                  <td className="py-3 px-4">
                    {r.status ? (
                      <StatusBadge value={r.status} />
                    ) : (
                      <span className="text-muted text-[11px]">Installed</span>
                    )}
                  </td>

                  <td className="py-3 px-4 text-muted">
                    {r.publisher || "Windows Registry"}
                  </td>

                  <td className="py-3 px-4 text-ink">
                    {r.hostname || "Windows Host"}
                  </td>

                  <td className="py-3 px-4 font-mono text-muted">
                    {r.macAddress}
                  </td>

                  <td className="py-3 px-4 max-w-xs truncate text-muted">
                    {r.summary || "Policy evaluation verified."}
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
