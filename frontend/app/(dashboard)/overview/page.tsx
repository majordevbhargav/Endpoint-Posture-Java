"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  Monitor,
  ShieldCheck,
  AlertTriangle,
  Flame,
  Radio,
  RefreshCw,
  Play,
  ArrowRight,
  ExternalLink,
  Shield,
} from "lucide-react";
import { api, EndpointResponse, AssessmentStatus } from "@/lib/api";
import { getCurrentUser } from "@/lib/auth";
import { RingGauge } from "@/components/dashboard/RingGauge";
import { StatusDonut } from "@/components/dashboard/StatusDonut";
import { StatCard } from "@/components/dashboard/StatCard";
import { RiskList, RiskItem } from "@/components/dashboard/RiskList";
import { ActivityFeed, ActivityItem } from "@/components/dashboard/ActivityFeed";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ConnectionDot } from "@/components/ui/ConnectionDot";

interface EndpointRow extends EndpointResponse {
  status?: AssessmentStatus;
}

function bandFor(status?: AssessmentStatus): "healthy" | "atRisk" | "critical" {
  if (status === "NON_COMPLIANT") return "atRisk";
  if (status === "ERROR") return "critical";
  return "healthy";
}

export default function OverviewPage() {
  const [rows, setRows] = useState<EndpointRow[] | null>(null);
  const [audit, setAudit] = useState<ActivityItem[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [searchFilter, setSearchFilter] = useState("");
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  async function loadData() {
    setRefreshing(true);
    try {
      const endpoints = await api.listEndpoints();
      const withStatus = await Promise.all(
        endpoints.map(async (e): Promise<EndpointRow> => {
          try {
            const latest = await api.latestPosture(e.id);
            return { ...e, status: latest.status };
          } catch {
            return { ...e };
          }
        })
      );
      setRows(withStatus);

      try {
        const entries = await api.auditActions();
        setAudit(
          entries.slice(0, 6).map((a) => ({
            id: a.id,
            time: a.occurredAt,
            title:
              a.actionType === "SHARE_POSTURE"
                ? "Posture shared with ISE"
                : a.actionType === "RESTRICT"
                ? "Endpoint restricted (ANC Quarantine)"
                : "Restriction cleared",
            subtitle: a.detail ?? undefined,
            kind:
              a.actionType === "SHARE_POSTURE"
                ? "share"
                : a.actionType === "RESTRICT"
                ? "restrict"
                : "clear",
          }))
        );
      } catch {
        /* optional */
      }
    } catch {
      setRows([]);
    } finally {
      setRefreshing(false);
    }
  }

  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 20000);
    return () => clearInterval(interval);
  }, []);

  const stats = useMemo(() => {
    const list = rows ?? [];
    const healthy = list.filter((r) => bandFor(r.status) === "healthy").length;
    const atRisk = list.filter((r) => bandFor(r.status) === "atRisk").length;
    const critical = list.filter((r) => bandFor(r.status) === "critical").length;
    const connected = list.filter((r) => r.connected).length;
    const total = list.length;
    const score = total === 0 ? 0 : Math.round((healthy / total) * 100);
    return { healthy, atRisk, critical, connected, total, score };
  }, [rows]);

  const risks: RiskItem[] = useMemo(
    () =>
      (rows ?? [])
        .filter((r) => bandFor(r.status) !== "healthy")
        .slice(0, 5)
        .map((r) => ({
          id: r.id,
          severity: bandFor(r.status) === "critical" ? "Critical" : "High",
          title: r.hostname ?? r.macAddress,
          subtitle:
            r.status === "ERROR"
              ? "CIM/WinRM collection error on last run"
              : "Firewall disabled or policy non-compliant",
        })),
    [rows]
  );

  async function triggerScanAll() {
    if (!rows || rows.length === 0) return;
    setActionNotice("Enqueuing posture checks for all endpoints…");
    let enqueued = 0;
    for (const r of rows) {
      try {
        await api.enqueueJob(r.id, "POSTURE_CHECK");
        enqueued++;
      } catch {
        // continue
      }
    }
    setActionNotice(`Enqueued ${enqueued} posture check jobs. View progress in Assessment Queue.`);
    setTimeout(() => setActionNotice(null), 5000);
  }

  const filteredEndpoints = useMemo(() => {
    if (!rows) return [];
    if (!searchFilter.trim()) return rows.slice(0, 8);
    const q = searchFilter.toLowerCase();
    return rows
      .filter(
        (r) =>
          r.macAddress.toLowerCase().includes(q) ||
          (r.hostname && r.hostname.toLowerCase().includes(q)) ||
          (r.ipAddress && r.ipAddress.toLowerCase().includes(q))
      )
      .slice(0, 8);
  }, [rows, searchFilter]);

  const user = getCurrentUser();
  const hour = new Date().getHours();
  const greeting = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";

  return (
    <div className="space-y-6">
      {/* Header with Greeting & Quick Actions */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight text-ink">
              {greeting}{user?.username ? `, ${user.username}` : ""}
            </h1>
            <span className="rounded-full bg-accent/15 px-2.5 py-0.5 text-xs font-semibold text-accent">
              Live Operations
            </span>
          </div>
          <p className="mt-1 text-xs text-muted">
            Continuous posture assessment and Cisco ISE session monitoring across your endpoint fleet.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={loadData}
            disabled={refreshing}
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:border-accent/40 disabled:opacity-50"
          >
            <RefreshCw size={13} className={refreshing ? "animate-spin text-accent" : "text-muted"} />
            <span>Refresh</span>
          </button>

          <button
            onClick={triggerScanAll}
            className="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-base transition hover:bg-accent/90"
          >
            <Play size={13} />
            <span>Scan Fleet</span>
          </button>
        </div>
      </div>

      {actionNotice && (
        <div className="rounded-lg border border-accent/30 bg-accent/10 px-4 py-2 text-xs font-medium text-accent">
          {actionNotice}
        </div>
      )}

      {/* Cisco ISE Status Banner */}
      <div className="panel flex flex-col justify-between gap-4 bg-panel2/60 p-4 sm:flex-row sm:items-center">
        <div className="flex items-center gap-3">
          <div className="relative flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl bg-good/15 text-good">
            <Radio size={18} />
            <span className="absolute -bottom-0.5 -right-0.5 h-2 w-2 rounded-full bg-good" />
          </div>
          <div>
            <div className="text-xs font-semibold text-ink">Cisco ISE Integration Active</div>
            <div className="text-[11px] text-muted">
              Auto-discovering active sessions &middot; {stats.connected} of {stats.total} devices
              currently connected
            </div>
          </div>
        </div>

        <div className="flex items-center gap-4 text-xs font-medium text-muted">
          <div>
            Poll Frequency: <span className="font-mono text-ink">15s</span>
          </div>
          <div className="h-3 w-px bg-border" />
          <div>
            Enforcement Mode: <span className="font-mono text-accent">Adaptive (ANC)</span>
          </div>
        </div>
      </div>

      {/* Main Posture Overview Section */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,0.9fr)]">
        {/* Environment Posture Ring Gauge & Quick KPI Cards */}
        <div className="panel flex flex-col justify-between p-5">
          <div className="mb-4 flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-muted">
              Fleet Posture Index
            </span>
            <span className="text-[11px] text-muted">Real-time composite</span>
          </div>

          <div className="flex flex-col items-center gap-6 sm:flex-row">
            <RingGauge value={stats.score} />
            <div className="grid w-full min-w-0 flex-1 grid-cols-2 gap-3">
              <StatCard icon={Monitor} label="Endpoints" value={stats.total} tone="accent" />
              <StatCard icon={ShieldCheck} label="Compliant" value={stats.healthy} tone="good" />
              <StatCard icon={AlertTriangle} label="At risk" value={stats.atRisk} tone="warn" />
              <StatCard icon={Flame} label="Critical" value={stats.critical} tone="bad" />
            </div>
          </div>

          <div className="mt-4 border-t border-border/60 pt-3 text-[11px] text-muted">
            <span className="font-semibold text-ink">Assessment rule:</span> Device compliance
            requires active Windows Firewall, port reachability checks passing, and verified
            installed software.
          </div>
        </div>

        {/* Posture Breakdown & Needs Attention */}
        <div className="flex min-w-0 flex-col gap-5">
          <div className="panel p-5">
            <div className="mb-4 text-xs font-semibold uppercase tracking-wider text-muted">
              Posture Distribution
            </div>
            <StatusDonut
              healthy={stats.healthy}
              atRisk={stats.atRisk}
              critical={stats.critical}
            />
          </div>

          <div className="panel flex-1 p-5">
            <div className="mb-3 flex items-center justify-between">
              <span className="text-xs font-semibold uppercase tracking-wider text-muted">
                Needs Immediate Attention
              </span>
              <Link href="/compliance" className="text-xs text-accent hover:underline">
                View all
              </Link>
            </div>
            <RiskList items={risks} />
          </div>
        </div>

        {/* Recent ISE Action Stream */}
        <div className="panel min-w-0 p-5">
          <div className="mb-4 flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-muted">
              Recent ISE Actions
            </span>
            <Link href="/audit" className="text-xs text-accent hover:underline">
              Audit log
            </Link>
          </div>
          <div className="min-w-0 break-words">
            <ActivityFeed items={audit} />
          </div>
        </div>
      </div>

      {/* Discovered Endpoints Quick Table */}
      <div className="panel p-5">
        <div className="mb-4 flex flex-col justify-between gap-3 sm:flex-row sm:items-center">
          <div>
            <div className="text-sm font-semibold text-ink">
              Discovered Endpoints{" "}
              <span className="font-normal text-muted">({rows?.length ?? 0} total)</span>
            </div>
            <div className="text-xs text-muted">
              Latest assessment and connection telemetry for devices reported by Cisco ISE.
            </div>
          </div>

          <div className="flex items-center gap-3">
            <input
              type="text"
              value={searchFilter}
              onChange={(e) => setSearchFilter(e.target.value)}
              placeholder="Filter devices…"
              className="w-48 rounded-lg border border-border bg-base px-2.5 py-1 text-xs text-ink placeholder:text-muted outline-none focus:border-accent"
            />
            <Link
              href="/endpoints"
              className="flex items-center gap-1 text-xs font-semibold text-accent hover:underline"
            >
              <span>Full Directory</span>
              <ArrowRight size={13} />
            </Link>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full min-w-[720px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border text-[11px] font-semibold text-muted">
                <th className="py-2.5 pr-4">Device / MAC</th>
                <th className="py-2.5 pr-4">IP Address</th>
                <th className="py-2.5 pr-4">Operating System</th>
                <th className="py-2.5 pr-4">ISE Session</th>
                <th className="py-2.5 pr-4">Posture Status</th>
                <th className="py-2.5 pr-4">Last Seen</th>
                <th className="py-2.5 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={7} className="py-8 text-center text-muted">
                    Loading endpoints from PostgreSQL…
                  </td>
                </tr>
              )}
              {rows !== null && filteredEndpoints.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-8 text-center text-muted">
                    No endpoints matching your filter. They appear automatically as Cisco ISE
                    detects active sessions.
                  </td>
                </tr>
              )}
              {filteredEndpoints.map((r) => (
                <tr key={r.id} className="transition hover:bg-ink/[0.03]">
                  <td className="py-2.5 pr-4">
                    <Link
                      href={`/endpoints/${r.id}`}
                      className="font-mono font-medium text-accent hover:underline"
                    >
                      {r.hostname ?? r.macAddress}
                    </Link>
                    {r.hostname && (
                      <div className="font-mono text-[10px] text-muted">{r.macAddress}</div>
                    )}
                  </td>
                  <td className="py-2.5 pr-4 font-mono text-ink">{r.ipAddress ?? "—"}</td>
                  <td className="max-w-[200px] truncate py-2.5 pr-4 text-muted">
                    {r.osName ?? "—"}
                  </td>
                  <td className="py-2.5 pr-4">
                    <ConnectionDot connected={r.connected} />
                  </td>
                  <td className="py-2.5 pr-4">
                    {r.status ? (
                      <StatusBadge value={r.status} />
                    ) : (
                      <span className="text-muted">Unassessed</span>
                    )}
                  </td>
                  <td className="py-2.5 pr-4 text-muted">
                    {new Date(r.lastSeenAt).toLocaleString([], {
                      month: "short",
                      day: "numeric",
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </td>
                  <td className="py-2.5 text-right">
                    <Link
                      href={`/endpoints/${r.id}`}
                      className="inline-flex items-center gap-1 rounded border border-border bg-panel px-2 py-1 text-[11px] font-medium text-ink hover:border-accent/40 hover:text-accent transition"
                    >
                      <span>Inspect</span>
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
