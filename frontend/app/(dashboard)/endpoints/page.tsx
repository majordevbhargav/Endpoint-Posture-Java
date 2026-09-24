"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  Monitor,
  Search,
  RefreshCw,
  Copy,
  Check,
  Play,
  Cpu,
  ExternalLink,
  ShieldAlert,
  Radio,
  Filter,
} from "lucide-react";
import { api, EndpointResponse, AssessmentStatus } from "@/lib/api";
import { ConnectionDot } from "@/components/ui/ConnectionDot";
import { StatusBadge } from "@/components/ui/StatusBadge";

interface EndpointWithStatus extends EndpointResponse {
  postureStatus?: AssessmentStatus;
}

export default function EndpointsPage() {
  const [endpoints, setEndpoints] = useState<EndpointWithStatus[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [connFilter, setConnFilter] = useState<"ALL" | "CONNECTED" | "DISCONNECTED">("ALL");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  const [actionMsg, setActionMsg] = useState<string | null>(null);

  async function loadEndpoints() {
    setLoading(true);
    setError(null);
    try {
      const list = await api.listEndpoints();
      const withStatus = await Promise.all(
        list.map(async (ep): Promise<EndpointWithStatus> => {
          try {
            const latest = await api.latestPosture(ep.id);
            return { ...ep, postureStatus: latest.status };
          } catch {
            return { ...ep };
          }
        })
      );
      setEndpoints(withStatus);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load endpoints.");
      setEndpoints([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadEndpoints();
  }, []);

  function copyText(text: string, key: string) {
    navigator.clipboard.writeText(text);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  }

  async function triggerJob(endpointId: string, type: "POSTURE_CHECK" | "HARDWARE_CHECK") {
    try {
      await api.enqueueJob(endpointId, type);
      setActionMsg(`Enqueued ${type === "POSTURE_CHECK" ? "Posture Check" : "Hardware Health Check"} job successfully.`);
      setTimeout(() => setActionMsg(null), 4000);
    } catch (err) {
      setActionMsg(`Failed to enqueue job: ${err instanceof Error ? err.message : "Error"}`);
    }
  }

  const stats = useMemo(() => {
    const list = endpoints ?? [];
    const total = list.length;
    const connected = list.filter((e) => e.connected).length;
    const compliant = list.filter((e) => e.postureStatus === "COMPLIANT").length;
    const atRisk = list.filter((e) => e.postureStatus === "NON_COMPLIANT" || e.postureStatus === "ERROR").length;
    return { total, connected, compliant, atRisk };
  }, [endpoints]);

  const filtered = useMemo(() => {
    if (!endpoints) return [];
    return endpoints.filter((ep) => {
      if (connFilter === "CONNECTED" && !ep.connected) return false;
      if (connFilter === "DISCONNECTED" && ep.connected) return false;

      if (statusFilter !== "ALL") {
        if (statusFilter === "UNASSESSED" && ep.postureStatus) return false;
        if (statusFilter !== "UNASSESSED" && ep.postureStatus !== statusFilter) return false;
      }

      if (search.trim()) {
        const q = search.toLowerCase();
        const matchesMac = ep.macAddress.toLowerCase().includes(q);
        const matchesIp = ep.ipAddress?.toLowerCase().includes(q);
        const matchesHost = ep.hostname?.toLowerCase().includes(q);
        const matchesOs = ep.osName?.toLowerCase().includes(q);
        if (!matchesMac && !matchesIp && !matchesHost && !matchesOs) return false;
      }

      return true;
    });
  }, [endpoints, connFilter, statusFilter, search]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Endpoints Directory</h1>
          <p className="mt-1 text-xs text-muted">
            All devices discovered via Cisco ISE session polling or agent posture submissions.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={loadEndpoints}
            disabled={loading}
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink hover:border-accent/40 disabled:opacity-50 transition"
          >
            <RefreshCw size={13} className={loading ? "animate-spin text-accent" : "text-muted"} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {actionMsg && (
        <div className="rounded-lg border border-accent/30 bg-accent/10 px-4 py-2 text-xs font-medium text-accent">
          {actionMsg}
        </div>
      )}

      {error && (
        <div className="rounded-lg border border-bad/30 bg-bad/10 p-3 text-xs text-bad">
          {error}
        </div>
      )}

      {/* Top Metric Cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Total Endpoints</span>
            <Monitor size={15} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.total}</div>
          <div className="text-[11px] text-muted">Known to platform</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>ISE Connected</span>
            <Radio size={15} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{stats.connected}</div>
          <div className="text-[11px] text-muted">Active network sessions</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Fully Compliant</span>
            <div className="h-2 w-2 rounded-full bg-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{stats.compliant}</div>
          <div className="text-[11px] text-muted">Policy passing</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>At Risk / Error</span>
            <ShieldAlert size={15} className="text-warn" />
          </div>
          <div className="mt-2 text-2xl font-bold text-warn">{stats.atRisk}</div>
          <div className="text-[11px] text-muted">Need investigation</div>
        </div>
      </div>

      {/* Filter and Search Bar */}
      <div className="panel flex flex-col justify-between gap-3 p-3 sm:flex-row sm:items-center">
        <div className="relative flex-1 max-w-md">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by MAC, IP, hostname, or OS…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none focus:border-accent"
          />
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/* Connection Filter */}
          <select
            value={connFilter}
            onChange={(e) => setConnFilter(e.target.value as any)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Connections</option>
            <option value="CONNECTED">Connected on ISE</option>
            <option value="DISCONNECTED">Disconnected</option>
          </select>

          {/* Status Filter */}
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Postures</option>
            <option value="COMPLIANT">Compliant</option>
            <option value="NON_COMPLIANT">Non-Compliant</option>
            <option value="ERROR">Error</option>
            <option value="UNASSESSED">Unassessed</option>
          </select>
        </div>
      </div>

      {/* Endpoints Table */}
      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[850px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="py-3 px-4">Device / Hostname</th>
                <th className="py-3 px-4">MAC Address</th>
                <th className="py-3 px-4">IP Address</th>
                <th className="py-3 px-4">Operating System</th>
                <th className="py-3 px-4">ISE Session</th>
                <th className="py-3 px-4">Posture Status</th>
                <th className="py-3 px-4">Last Seen</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {endpoints === null && (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-muted">
                    Loading endpoints directory…
                  </td>
                </tr>
              )}
              {endpoints !== null && filtered.length === 0 && (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-muted">
                    No endpoints match the specified criteria.
                  </td>
                </tr>
              )}
              {filtered.map((ep) => (
                <tr key={ep.id} className="transition hover:bg-ink/[0.02]">
                  <td className="py-3 px-4 font-medium text-ink">
                    <Link
                      href={`/endpoints/${ep.id}`}
                      className="text-accent hover:underline flex items-center gap-1.5"
                    >
                      <Monitor size={14} className="text-muted" />
                      <span className="font-semibold">{ep.hostname || "Unnamed Host"}</span>
                    </Link>
                  </td>

                  <td className="py-3 px-4 font-mono text-ink">
                    <div className="flex items-center gap-1.5">
                      <span>{ep.macAddress}</span>
                      <button
                        onClick={() => copyText(ep.macAddress, `mac-${ep.id}`)}
                        className="text-muted hover:text-ink"
                        title="Copy MAC"
                      >
                        {copiedKey === `mac-${ep.id}` ? (
                          <Check size={12} className="text-good" />
                        ) : (
                          <Copy size={12} />
                        )}
                      </button>
                    </div>
                  </td>

                  <td className="py-3 px-4 font-mono text-muted">
                    {ep.ipAddress ? (
                      <div className="flex items-center gap-1.5">
                        <span className="text-ink">{ep.ipAddress}</span>
                        <button
                          onClick={() => copyText(ep.ipAddress!, `ip-${ep.id}`)}
                          className="text-muted hover:text-ink"
                          title="Copy IP"
                        >
                          {copiedKey === `ip-${ep.id}` ? (
                            <Check size={12} className="text-good" />
                          ) : (
                            <Copy size={12} />
                          )}
                        </button>
                      </div>
                    ) : (
                      "—"
                    )}
                  </td>

                  <td className="max-w-[200px] truncate py-3 px-4 text-muted">
                    {ep.osName || "Unknown OS"}
                  </td>

                  <td className="py-3 px-4">
                    <ConnectionDot connected={ep.connected} />
                  </td>

                  <td className="py-3 px-4">
                    {ep.postureStatus ? (
                      <StatusBadge value={ep.postureStatus} />
                    ) : (
                      <span className="text-muted text-[11px]">Unassessed</span>
                    )}
                  </td>

                  <td className="py-3 px-4 text-muted">
                    {new Date(ep.lastSeenAt).toLocaleString([], {
                      month: "short",
                      day: "numeric",
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </td>

                  <td className="py-3 px-4 text-right">
                    <div className="flex items-center justify-end gap-1.5">
                      <button
                        onClick={() => triggerJob(ep.id, "POSTURE_CHECK")}
                        title="Run Posture Check"
                        className="rounded border border-border bg-panel p-1.5 text-muted hover:border-accent/40 hover:text-accent transition"
                      >
                        <Play size={12} />
                      </button>

                      <button
                        onClick={() => triggerJob(ep.id, "HARDWARE_CHECK")}
                        title="Run Hardware Health Check"
                        className="rounded border border-border bg-panel p-1.5 text-muted hover:border-accent/40 hover:text-accent transition"
                      >
                        <Cpu size={12} />
                      </button>

                      <Link
                        href={`/endpoints/${ep.id}`}
                        title="Inspect 360°"
                        className="rounded border border-border bg-panel p-1.5 text-muted hover:border-accent/40 hover:text-ink transition"
                      >
                        <ExternalLink size={12} />
                      </Link>
                    </div>
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