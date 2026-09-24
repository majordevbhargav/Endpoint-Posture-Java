"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  Cpu,
  Server,
  HardDrive,
  Battery,
  RefreshCw,
  Play,
  Monitor,
  Search,
  AlertTriangle,
  ChevronDown,
  ChevronUp,
  Wrench,
} from "lucide-react";
import { api, EndpointResponse, HardwareHealthResponse, HardwareBand } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";

type Row = {
  e: EndpointResponse;
  hw: HardwareHealthResponse | null;
};

function ScoreBar({ value, label }: { value: number | null | undefined; label: string }) {
  if (value == null) {
    return (
      <div className="flex items-center gap-1.5 text-xs text-muted">
        <span>—</span>
      </div>
    );
  }

  const isGood = value >= 80;
  const isWarn = value >= 60 && value < 80;
  const barColor = isGood ? "bg-good" : isWarn ? "bg-warn" : "bg-bad";
  const textColor = isGood ? "text-good" : isWarn ? "text-warn" : "text-bad";

  return (
    <div className="flex flex-col gap-1 min-w-[70px]">
      <div className="flex items-center justify-between text-[11px]">
        <span className="font-mono font-bold leading-none">{value}</span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-border/60">
        <div
          className={`h-full rounded-full ${barColor}`}
          style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
        />
      </div>
    </div>
  );
}

export default function HardwarePage() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState("");
  const [bandFilter, setBandFilter] = useState<string>("ALL");
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const loadData = async () => {
    setLoading(true);
    try {
      const eps = await api.listEndpoints();
      const withHw = await Promise.all(
        eps.map(async (e) => ({
          e,
          hw: await api.latestHardware(e.id).catch(() => null),
        }))
      );
      setRows(withHw);
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 30000);
    return () => clearInterval(interval);
  }, []);

  const stats = useMemo(() => {
    const list = rows ?? [];
    const withReport = list.filter((r) => r.hw !== null);
    const avgScore =
      withReport.length === 0
        ? 0
        : Math.round(
            withReport.reduce((acc, r) => acc + (r.hw?.overallScore ?? 0), 0) / withReport.length
          );
    const criticalCount = withReport.filter(
      (r) => r.hw?.overallBand === "CRITICAL" || r.hw?.overallBand === "DEGRADED"
    ).length;
    const batteryIssues = withReport.filter(
      (r) => r.hw?.batteryScore != null && r.hw.batteryScore < 70
    ).length;
    const totalRecommendations = withReport.reduce(
      (acc, r) => acc + (r.hw?.recommendations?.length ?? 0),
      0
    );

    return { avgScore, criticalCount, batteryIssues, totalRecommendations, total: list.length };
  }, [rows]);

  async function triggerHardwareAll() {
    if (!rows || rows.length === 0) return;
    setActionNotice("Enqueuing hardware health checks for all endpoints…");
    let count = 0;
    for (const { e } of rows) {
      try {
        await api.enqueueJob(e.id, "HARDWARE_CHECK");
        count++;
      } catch {
        // continue
      }
    }
    setActionNotice(`Enqueued ${count} hardware health checks. JobWorker will run agent script.`);
    setTimeout(() => setActionNotice(null), 5000);
  }

  const filtered = useMemo(() => {
    if (!rows) return [];
    return rows.filter(({ e, hw }) => {
      if (bandFilter !== "ALL") {
        if (bandFilter === "NO_REPORT" && hw !== null) return false;
        if (bandFilter !== "NO_REPORT" && hw?.overallBand !== bandFilter) return false;
      }

      if (search.trim()) {
        const q = search.toLowerCase();
        const matchesName = e.hostname?.toLowerCase().includes(q);
        const matchesMac = e.macAddress.toLowerCase().includes(q);
        const matchesModel = hw?.model?.toLowerCase().includes(q);
        if (!matchesName && !matchesMac && !matchesModel) return false;
      }

      return true;
    });
  }, [rows, bandFilter, search]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Hardware Health Telemetry</h1>
          <p className="mt-1 text-xs text-muted">
            Telemetry metrics for CPU utilization, memory pressure, storage life, and battery health.
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
            onClick={triggerHardwareAll}
            className="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-base transition hover:bg-accent/90"
          >
            <Play size={13} />
            <span>Check All Hardware</span>
          </button>
        </div>
      </div>

      {actionNotice && (
        <div className="rounded-lg border border-accent/30 bg-accent/10 px-4 py-2 text-xs font-medium text-accent">
          {actionNotice}
        </div>
      )}

      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Fleet Average Health</span>
            <Cpu size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">
            {stats.avgScore} <span className="text-xs text-muted">/100</span>
          </div>
          <div className="text-[11px] text-muted">Composite score</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Critical / Degraded</span>
            <AlertTriangle size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{stats.criticalCount}</div>
          <div className="text-[11px] text-muted">Require maintenance</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Battery Warnings</span>
            <Battery size={16} className="text-warn" />
          </div>
          <div className="mt-2 text-2xl font-bold text-warn">{stats.batteryIssues}</div>
          <div className="text-[11px] text-muted">Battery health &lt; 70%</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Open Recommendations</span>
            <Wrench size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.totalRecommendations}</div>
          <div className="text-[11px] text-muted">Actionable suggestions</div>
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
            placeholder="Search by device, MAC, or model…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none focus:border-accent"
          />
        </div>

        <div className="flex items-center gap-2">
          <select
            value={bandFilter}
            onChange={(e) => setBandFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Health Bands</option>
            <option value="HEALTHY">Healthy</option>
            <option value="WARNING">Warning</option>
            <option value="DEGRADED">Degraded</option>
            <option value="CRITICAL">Critical</option>
            <option value="NO_REPORT">No Report</option>
          </select>
        </div>
      </div>

      {/* Hardware Telemetry Table */}
      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[840px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="py-3 px-4">Device</th>
                <th className="py-3 px-4">Overall Band</th>
                <th className="py-3 px-4">CPU Score</th>
                <th className="py-3 px-4">Memory Score</th>
                <th className="py-3 px-4">Storage Score</th>
                <th className="py-3 px-4">Battery Score</th>
                <th className="py-3 px-4">Recommendations</th>
                <th className="py-3 px-4">Last Collected</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={9} className="py-12 text-center text-muted">
                    Loading hardware telemetry records…
                  </td>
                </tr>
              )}
              {rows !== null && filtered.length === 0 && (
                <tr>
                  <td colSpan={9} className="py-12 text-center text-muted">
                    No matching hardware reports found.
                  </td>
                </tr>
              )}
              {filtered.map(({ e, hw }) => {
                const isExpanded = expandedId === e.id;
                return (
                  <tr key={e.id} className="transition hover:bg-ink/[0.02]">
                    <td className="py-3 px-4 font-medium text-ink">
                      <Link
                        href={`/endpoints/${e.id}`}
                        className="font-mono text-accent hover:underline flex items-center gap-1.5"
                      >
                        <Monitor size={13} className="text-muted" />
                        <span>{e.hostname ?? e.macAddress}</span>
                      </Link>
                      {hw?.model && (
                        <div className="text-[10px] text-muted truncate max-w-[180px]">
                          {hw.manufacturer ? `${hw.manufacturer} ` : ""}{hw.model}
                        </div>
                      )}
                    </td>

                    <td className="py-3 px-4">
                      {hw ? (
                        <div className="flex items-center gap-1.5">
                          <StatusBadge value={hw.overallBand} />
                          <span className="font-mono text-xs font-bold text-ink">
                            {hw.overallScore}
                          </span>
                        </div>
                      ) : (
                        <span className="text-muted text-[11px]">No Report</span>
                      )}
                    </td>

                    <td className="py-3 px-4">
                      <ScoreBar value={hw?.cpuScore} label="CPU" />
                    </td>

                    <td className="py-3 px-4">
                      <ScoreBar value={hw?.memoryScore} label="RAM" />
                    </td>

                    <td className="py-3 px-4">
                      <ScoreBar value={hw?.storageScore} label="Disk" />
                    </td>

                    <td className="py-3 px-4">
                      <ScoreBar value={hw?.batteryScore} label="Bat" />
                    </td>

                    <td className="py-3 px-4">
                      {hw && hw.recommendations.length > 0 ? (
                        <span className="rounded-full bg-warn/15 px-2 py-0.5 text-[10px] font-bold text-warn">
                          {hw.recommendations.length} action{hw.recommendations.length > 1 ? "s" : ""}
                        </span>
                      ) : (
                        <span className="text-muted text-[11px]">None</span>
                      )}
                    </td>

                    <td className="py-3 px-4 text-muted">
                      {hw
                        ? new Date(hw.collectedAt).toLocaleString([], {
                            month: "short",
                            day: "numeric",
                            hour: "2-digit",
                            minute: "2-digit",
                          })
                        : "—"}
                    </td>

                    <td className="py-3 px-4 text-right">
                      {hw && hw.recommendations.length > 0 ? (
                        <button
                          onClick={() => setExpandedId(isExpanded ? null : e.id)}
                          className="inline-flex items-center gap-1 rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted hover:text-ink transition"
                        >
                          <span>{isExpanded ? "Hide" : "Inspect"}</span>
                          {isExpanded ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                        </button>
                      ) : (
                        <Link
                          href={`/endpoints/${e.id}`}
                          className="rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted hover:text-accent transition"
                        >
                          Details
                        </Link>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {/* Expanded Recommendations Drawer */}
        {expandedId && (
          <div className="border-t border-border bg-panel2/40 p-4">
            {(() => {
              const item = rows?.find((r) => r.e.id === expandedId);
              if (!item || !item.hw) return null;
              return (
                <div>
                  <div className="mb-2 text-xs font-bold text-ink">
                    Maintenance Recommendations for {item.e.hostname || item.e.macAddress}:
                  </div>
                  <div className="space-y-1.5">
                    {item.hw.recommendations.map((rec, i) => (
                      <div
                        key={i}
                        className="flex items-start gap-2 rounded-lg border border-border/80 bg-panel p-2.5 text-xs"
                      >
                        <span
                          className={`rounded px-1.5 py-0.5 text-[10px] font-bold ${
                            rec.priority === "HIGH"
                              ? "bg-bad/15 text-bad"
                              : rec.priority === "MEDIUM"
                              ? "bg-warn/15 text-warn"
                              : "bg-good/15 text-good"
                          }`}
                        >
                          {rec.priority}
                        </span>
                        <div>
                          <span className="font-semibold text-ink">{rec.area}:</span>{" "}
                          <span className="text-muted">{rec.action}</span>
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
