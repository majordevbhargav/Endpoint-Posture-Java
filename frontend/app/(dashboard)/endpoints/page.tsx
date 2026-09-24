"use client";

import { useEffect, useMemo, useState, useCallback } from "react";
import Link from "next/link";
import {
  Monitor, Search, RefreshCw, Copy, Check, Play, Cpu, ExternalLink,
  ShieldAlert, Radio, ChevronDown, ChevronUp, Wifi, WifiOff,
  HardDrive, Battery, Server, Info,
} from "lucide-react";
import {
  api, EndpointResponse, AssessmentResponse, HardwareHealthResponse, AssessmentStatus,
} from "@/lib/api";
import { ConnectionDot } from "@/components/ui/ConnectionDot";
import { StatusBadge } from "@/components/ui/StatusBadge";

interface EndpointWithDetails extends EndpointResponse {
  postureStatus?: AssessmentStatus;
  posture?: AssessmentResponse | null;
  hardware?: HardwareHealthResponse | null;
}

function timeSince(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  const hours = Math.floor(mins / 60);
  const days = Math.floor(hours / 24);
  if (days > 0) return `${days}d ago`;
  if (hours > 0) return `${hours}h ago`;
  if (mins > 0) return `${mins}m ago`;
  return "just now";
}

function ScoreBar({ value }: { value: number | null | undefined }) {
  if (value == null) return <span className="text-muted text-[11px]">—</span>;
  const color = value >= 80 ? "bg-good" : value >= 60 ? "bg-warn" : "bg-bad";
  const textColor = value >= 80 ? "text-good" : value >= 60 ? "text-warn" : "text-bad";
  return (
    <div className="flex items-center gap-1.5">
      <div className="h-1.5 w-16 overflow-hidden rounded-full bg-border/60">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${value}%` }} />
      </div>
      <span className={`font-mono text-[11px] font-bold ${textColor}`}>{value}</span>
    </div>
  );
}

function ExpandedPanel({ ep }: { ep: EndpointWithDetails }) {
  const posture = ep.posture;
  const hardware = ep.hardware;
  return (
    <div className="border-t border-border/60 bg-panel2/30 px-4 py-5">
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2 xl:grid-cols-3">
        {/* Identity & Network */}
        <div className="space-y-3">
          <div className="text-[10px] font-bold uppercase tracking-widest text-muted">Identity & Network</div>
          <div className="grid grid-cols-2 gap-2 text-[11px]">
            <div className="rounded-lg bg-base/70 p-2.5">
              <div className="text-muted">MAC Address</div>
              <div className="mt-0.5 font-mono font-semibold text-ink break-all">{ep.macAddress}</div>
            </div>
            <div className="rounded-lg bg-base/70 p-2.5">
              <div className="text-muted">IP Address</div>
              <div className="mt-0.5 font-mono font-semibold text-ink">{ep.ipAddress || "—"}</div>
            </div>
            <div className="rounded-lg bg-base/70 p-2.5">
              <div className="text-muted">Operating System</div>
              <div className="mt-0.5 font-semibold text-ink truncate">{ep.osName || "Windows"}</div>
            </div>
            <div className="rounded-lg bg-base/70 p-2.5">
              <div className="text-muted">OS Version</div>
              <div className="mt-0.5 font-mono text-ink truncate text-[10px]">{ep.osVersion || "—"}</div>
            </div>
            <div className="col-span-2 rounded-lg bg-base/70 p-2.5">
              <div className="text-muted mb-1">ISE Session Status</div>
              {ep.connected ? (
                <span className="inline-flex items-center gap-1.5 text-good font-semibold">
                  <Wifi size={12} /> Connected — Active ISE session
                </span>
              ) : (
                <span className="inline-flex items-center gap-1.5 text-muted font-medium">
                  <WifiOff size={12} /> Offline — Last seen {timeSince(ep.lastSeenAt)}
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Posture Assessment */}
        <div className="space-y-3">
          <div className="text-[10px] font-bold uppercase tracking-widest text-muted">Latest Posture Assessment</div>
          {!posture ? (
            <div className="rounded-lg border border-border/60 bg-base/50 p-4 text-center">
              <Info size={18} className="mx-auto mb-2 text-muted" />
              <div className="text-xs text-muted">No assessment run yet</div>
              <div className="mt-0.5 text-[10px] text-muted/70">Click ▶ to dispatch posture check</div>
            </div>
          ) : (
            <div className="space-y-2">
              <div className="flex items-center justify-between rounded-lg bg-base/70 p-2.5 text-[11px]">
                <span className="text-muted">Overall Result</span>
                <StatusBadge value={posture.status} />
              </div>
              <div className="flex items-center justify-between rounded-lg bg-base/70 p-2.5 text-[11px]">
                <span className="text-muted">Assessed At</span>
                <span className="font-mono text-ink">
                  {new Date(posture.completedAt || posture.startedAt).toLocaleString([], {
                    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
                  })}
                </span>
              </div>
              {posture.checks.map((c) => (
                <div key={c.id} className="flex items-center justify-between rounded-lg bg-base/70 p-2.5 text-[11px]">
                  <span className="text-muted">
                    {c.checkType === "FIREWALL" ? "Windows Firewall"
                      : c.checkType === "OPEN_PORTS" ? "Listening Ports"
                      : c.checkType === "APPLICATIONS" ? "Application Control"
                      : c.checkType}
                  </span>
                  <StatusBadge value={c.status} />
                </div>
              ))}
              {posture.detail && (
                <div className="rounded-lg bg-base/70 p-2.5 text-[10px] font-mono text-muted break-words">
                  {posture.detail}
                </div>
              )}
            </div>
          )}
        </div>

        {/* Hardware Health */}
        <div className="space-y-3">
          <div className="text-[10px] font-bold uppercase tracking-widest text-muted">Hardware Health</div>
          {!hardware ? (
            <div className="rounded-lg border border-border/60 bg-base/50 p-4 text-center">
              <Cpu size={18} className="mx-auto mb-2 text-muted" />
              <div className="text-xs text-muted">No hardware report</div>
              <div className="mt-0.5 text-[10px] text-muted/70">Click CPU icon to dispatch check</div>
            </div>
          ) : (
            <div className="space-y-2">
              <div className="flex items-center justify-between rounded-lg bg-base/70 p-2.5 text-[11px]">
                <span className="text-muted">Overall Score</span>
                <div className="flex items-center gap-2">
                  <ScoreBar value={hardware.overallScore} />
                  <StatusBadge value={hardware.overallBand} />
                </div>
              </div>
              <div className="flex items-center justify-between rounded-lg bg-base/70 p-2.5 text-[11px]">
                <span className="flex items-center gap-1.5 text-muted"><Cpu size={11} />CPU</span>
                <ScoreBar value={hardware.cpuScore} />
              </div>
              <div className="flex items-center justify-between rounded-lg bg-base/70 p-2.5 text-[11px]">
                <span className="flex items-center gap-1.5 text-muted"><Server size={11} />Memory</span>
                <ScoreBar value={hardware.memoryScore} />
              </div>
              <div className="flex items-center justify-between rounded-lg bg-base/70 p-2.5 text-[11px]">
                <span className="flex items-center gap-1.5 text-muted"><HardDrive size={11} />Storage</span>
                <ScoreBar value={hardware.storageScore} />
              </div>
              {hardware.batteryScore != null && (
                <div className="flex items-center justify-between rounded-lg bg-base/70 p-2.5 text-[11px]">
                  <span className="flex items-center gap-1.5 text-muted"><Battery size={11} />Battery</span>
                  <ScoreBar value={hardware.batteryScore} />
                </div>
              )}
              {(hardware.manufacturer || hardware.model) && (
                <div className="rounded-lg bg-base/70 p-2.5 text-[11px]">
                  <span className="text-muted">Device: </span>
                  <span className="font-semibold text-ink">{hardware.manufacturer} {hardware.model}</span>
                </div>
              )}
              {hardware.recommendations.length > 0 && (
                <div className="rounded-lg border border-warn/25 bg-warn/5 p-2.5 text-[11px]">
                  <span className="font-semibold text-warn">
                    {hardware.recommendations.length} maintenance action{hardware.recommendations.length > 1 ? "s" : ""} recommended
                  </span>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Footer */}
      <div className="mt-4 flex items-center justify-between border-t border-border/40 pt-4">
        <div className="text-[11px] text-muted">
          <span>Last seen: </span>
          <span className="font-semibold text-ink">
            {new Date(ep.lastSeenAt).toLocaleString([], {
              month: "short", day: "numeric", year: "numeric", hour: "2-digit", minute: "2-digit",
            })}
          </span>
          {!ep.connected && (
            <span className="ml-2 rounded-full bg-muted/10 px-2 py-0.5 text-[10px] text-muted">
              Offline — historical data shown
            </span>
          )}
        </div>
        <Link
          href={`/endpoints/${ep.id}`}
          className="flex items-center gap-1.5 rounded-lg border border-accent/30 bg-accent/10 px-3 py-1.5 text-xs font-semibold text-accent hover:bg-accent/20 transition"
        >
          <span>Open Full 360° View</span>
          <ExternalLink size={11} />
        </Link>
      </div>
    </div>
  );
}

export default function EndpointsPage() {
  const [endpoints, setEndpoints] = useState<EndpointWithDetails[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [connFilter, setConnFilter] = useState<"ALL" | "CONNECTED" | "DISCONNECTED">("ALL");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  const [actionMsg, setActionMsg] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [loadingDetail, setLoadingDetail] = useState<string | null>(null);

  const loadEndpoints = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await api.listEndpoints();
      const withStatus = await Promise.all(
        list.map(async (ep): Promise<EndpointWithDetails> => {
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
  }, []);

  useEffect(() => { loadEndpoints(); }, [loadEndpoints]);

  function copyText(text: string, key: string) {
    navigator.clipboard.writeText(text);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  }

  async function triggerJob(endpointId: string, type: "POSTURE_CHECK" | "HARDWARE_CHECK") {
    try {
      await api.enqueueJob(endpointId, type);
      setActionMsg(`${type === "POSTURE_CHECK" ? "Posture Check" : "Hardware Health Check"} job enqueued.`);
      setTimeout(() => setActionMsg(null), 4000);
    } catch (err) {
      setActionMsg(`Failed to enqueue: ${err instanceof Error ? err.message : "Error"}`);
    }
  }

  async function toggleExpand(ep: EndpointWithDetails) {
    if (expandedId === ep.id) { setExpandedId(null); return; }
    setExpandedId(ep.id);
    if (ep.posture === undefined || ep.hardware === undefined) {
      setLoadingDetail(ep.id);
      const [postureRes, hwRes] = await Promise.allSettled([
        api.latestPosture(ep.id),
        api.latestHardware(ep.id),
      ]);
      setEndpoints((prev) =>
        prev ? prev.map((e) =>
          e.id === ep.id ? {
            ...e,
            posture: postureRes.status === "fulfilled" ? postureRes.value : null,
            hardware: hwRes.status === "fulfilled" ? hwRes.value : null,
          } : e
        ) : prev
      );
      setLoadingDetail(null);
    }
  }

  const stats = useMemo(() => {
    const list = endpoints ?? [];
    return {
      total: list.length,
      connected: list.filter((e) => e.connected).length,
      compliant: list.filter((e) => e.postureStatus === "COMPLIANT").length,
      atRisk: list.filter((e) => e.postureStatus === "NON_COMPLIANT" || e.postureStatus === "ERROR").length,
    };
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
        if (!ep.macAddress.toLowerCase().includes(q) &&
            !ep.ipAddress?.toLowerCase().includes(q) &&
            !ep.hostname?.toLowerCase().includes(q) &&
            !ep.osName?.toLowerCase().includes(q)) return false;
      }
      return true;
    });
  }, [endpoints, connFilter, statusFilter, search]);

  const offlineCount = endpoints?.filter((e) => !e.connected).length ?? 0;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Endpoints Directory</h1>
          <p className="mt-1 text-xs text-muted">
            All devices discovered via Cisco ISE session polling. Click any row to expand full telemetry.
            Disconnected devices retain their last-known posture and hardware data.
          </p>
        </div>
        <button
          onClick={loadEndpoints}
          disabled={loading}
          className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink hover:border-accent/40 disabled:opacity-50 transition"
        >
          <RefreshCw size={13} className={loading ? "animate-spin text-accent" : "text-muted"} />
          <span>Refresh</span>
        </button>
      </div>

      {actionMsg && (
        <div className="rounded-lg border border-accent/30 bg-accent/10 px-4 py-2 text-xs font-medium text-accent">{actionMsg}</div>
      )}
      {error && (
        <div className="rounded-lg border border-bad/30 bg-bad/10 p-3 text-xs text-bad">{error}</div>
      )}

      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {[
          { label: "Total Endpoints", value: stats.total, color: "text-ink", sub: "Known to platform", icon: <Monitor size={15} className="text-accent" /> },
          { label: "ISE Connected", value: stats.connected, color: "text-good", sub: "Active sessions", icon: <Radio size={15} className="text-good" /> },
          { label: "Fully Compliant", value: stats.compliant, color: "text-good", sub: "Policy passing", icon: <div className="h-2 w-2 rounded-full bg-good" /> },
          { label: "At Risk / Error", value: stats.atRisk, color: "text-warn", sub: "Need investigation", icon: <ShieldAlert size={15} className="text-warn" /> },
        ].map(({ label, value, color, sub, icon }) => (
          <div key={label} className="panel p-4">
            <div className="flex items-center justify-between text-xs text-muted"><span>{label}</span>{icon}</div>
            <div className={`mt-2 text-2xl font-bold ${color}`}>{value}</div>
            <div className="text-[11px] text-muted">{sub}</div>
          </div>
        ))}
      </div>

      {/* Filters */}
      <div className="panel flex flex-col justify-between gap-3 p-3 sm:flex-row sm:items-center">
        <div className="relative flex-1 max-w-md">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input type="text" value={search} onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by MAC, IP, hostname, or OS…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none focus:border-accent" />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <select value={connFilter} onChange={(e) => setConnFilter(e.target.value as any)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent">
            <option value="ALL">All Connections</option>
            <option value="CONNECTED">Connected on ISE</option>
            <option value="DISCONNECTED">Disconnected</option>
          </select>
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent">
            <option value="ALL">All Postures</option>
            <option value="COMPLIANT">Compliant</option>
            <option value="NON_COMPLIANT">Non-Compliant</option>
            <option value="ERROR">Error</option>
            <option value="UNASSESSED">Unassessed</option>
          </select>
        </div>
      </div>

      {/* Accordion Table */}
      <div className="panel overflow-hidden">
        {/* Header row */}
        <div className="border-b border-border bg-panel2/40 px-4 py-3 hidden lg:grid lg:grid-cols-[2fr_1.4fr_1fr_1fr_0.7fr_0.8fr_0.8fr_auto] gap-3 text-[11px] font-semibold text-muted">
          <span>Device / Hostname</span>
          <span>MAC Address</span>
          <span>IP Address</span>
          <span>Operating System</span>
          <span>ISE</span>
          <span>Posture</span>
          <span>Last Seen</span>
          <span className="text-right">Actions</span>
        </div>

        <div className="divide-y divide-border/40">
          {endpoints === null && (
            <div className="py-12 text-center text-xs text-muted">Loading endpoints directory…</div>
          )}
          {endpoints !== null && filtered.length === 0 && (
            <div className="py-12 text-center text-xs text-muted">No endpoints match the specified criteria.</div>
          )}

          {filtered.map((ep) => {
            const isExpanded = expandedId === ep.id;
            const isLoadingThis = loadingDetail === ep.id;
            return (
              <div key={ep.id}>
                <div
                  onClick={() => toggleExpand(ep)}
                  className={[
                    "grid lg:grid-cols-[2fr_1.4fr_1fr_1fr_0.7fr_0.8fr_0.8fr_auto] gap-3 items-center px-4 py-3 text-xs cursor-pointer transition hover:bg-ink/[0.02]",
                    isExpanded ? "bg-accent/[0.03] border-l-2 border-l-accent" : "",
                    !ep.connected ? "opacity-75" : "",
                  ].join(" ")}
                >
                  {/* Device */}
                  <div className="flex items-center gap-2 min-w-0">
                    <div className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-lg ${ep.connected ? "bg-accent/15 text-accent" : "bg-muted/10 text-muted"}`}>
                      <Monitor size={13} />
                    </div>
                    <div className="min-w-0">
                      <div className="truncate font-semibold text-ink">{ep.hostname || "Unnamed Host"}</div>
                      {!ep.connected && <div className="text-[10px] text-muted/70">offline · {timeSince(ep.lastSeenAt)}</div>}
                    </div>
                  </div>

                  {/* MAC */}
                  <div className="flex items-center gap-1 font-mono text-[11px] text-ink min-w-0">
                    <span className="truncate">{ep.macAddress}</span>
                    <button onClick={(e) => { e.stopPropagation(); copyText(ep.macAddress, `mac-${ep.id}`); }}
                      className="flex-shrink-0 text-muted hover:text-ink" title="Copy">
                      {copiedKey === `mac-${ep.id}` ? <Check size={11} className="text-good" /> : <Copy size={11} />}
                    </button>
                  </div>

                  {/* IP */}
                  <div className="font-mono text-[11px] text-ink">{ep.ipAddress || <span className="text-muted">—</span>}</div>

                  {/* OS */}
                  <div className="truncate text-[11px] text-muted">{ep.osName || "—"}</div>

                  {/* ISE */}
                  <div onClick={(e) => e.stopPropagation()}><ConnectionDot connected={ep.connected} /></div>

                  {/* Posture */}
                  <div onClick={(e) => e.stopPropagation()}>
                    {ep.postureStatus ? <StatusBadge value={ep.postureStatus} /> : <span className="text-muted text-[11px]">—</span>}
                  </div>

                  {/* Last Seen */}
                  <div className="text-[11px] text-muted">
                    {new Date(ep.lastSeenAt).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}
                  </div>

                  {/* Actions */}
                  <div className="flex items-center justify-end gap-1.5" onClick={(e) => e.stopPropagation()}>
                    <button onClick={() => triggerJob(ep.id, "POSTURE_CHECK")} title="Posture Check"
                      className="rounded border border-border bg-panel p-1.5 text-muted hover:border-accent/40 hover:text-accent transition">
                      <Play size={11} />
                    </button>
                    <button onClick={() => triggerJob(ep.id, "HARDWARE_CHECK")} title="Hardware Check"
                      className="rounded border border-border bg-panel p-1.5 text-muted hover:border-accent/40 hover:text-accent transition">
                      <Cpu size={11} />
                    </button>
                    <button onClick={() => toggleExpand(ep)} title={isExpanded ? "Collapse" : "Expand"}
                      className={`rounded border p-1.5 transition ${isExpanded ? "border-accent/40 bg-accent/10 text-accent" : "border-border bg-panel text-muted hover:text-ink"}`}>
                      {isLoadingThis ? <RefreshCw size={11} className="animate-spin" /> : isExpanded ? <ChevronUp size={11} /> : <ChevronDown size={11} />}
                    </button>
                  </div>
                </div>

                {/* Expanded panel */}
                {isExpanded && (
                  isLoadingThis ? (
                    <div className="border-t border-border/60 bg-panel2/30 py-6 text-center text-xs text-muted">
                      <RefreshCw size={13} className="inline animate-spin mr-2 text-accent" />
                      Loading telemetry data…
                    </div>
                  ) : <ExpandedPanel ep={ep} />
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* Offline notice */}
      {offlineCount > 0 && (
        <div className="flex items-start gap-3 rounded-xl border border-border/60 bg-panel2/40 p-4 text-xs text-muted">
          <Info size={14} className="flex-shrink-0 text-accent mt-0.5" />
          <div>
            <span className="font-semibold text-ink">{offlineCount} device{offlineCount > 1 ? "s" : ""} currently offline.</span>{" "}
            These endpoints are not active on the Cisco ISE network right now, but their last-known posture assessments,
            hardware telemetry, and configuration data are preserved and shown when you expand a row.
            They will reconnect automatically when ISE detects their next session.
          </div>
        </div>
      )}
    </div>
  );
}
