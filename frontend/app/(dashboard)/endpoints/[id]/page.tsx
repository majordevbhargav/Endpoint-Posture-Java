"use client";

import { useEffect, useState, useCallback } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import {
  Monitor,
  ShieldCheck,
  ShieldX,
  Play,
  Cpu,
  RefreshCw,
  ArrowLeft,
  CheckCircle2,
  HardDrive,
  Battery,
  Server,
  AlertTriangle,
  Wifi,
  WifiOff,
  Activity,
  ShieldAlert,
} from "lucide-react";
import {
  api,
  EndpointResponse,
  AssessmentResponse,
  HardwareHealthResponse,
  JobResponse,
  IseActionAudit,
  SessionEvent,
  DiagnosticResponse,
  SecurityIndicatorResponse,
} from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ConnectionDot } from "@/components/ui/ConnectionDot";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { TrendChart, ChartSeries } from "@/components/dashboard/TrendChart";
import { SecurityIndicatorsTab } from "@/components/endpoints/SecurityIndicatorsTab";
import { can, DENIED_HINT } from "@/lib/permissions";


type ActionResult = { success: boolean; detail: string };

interface ConfirmState {
  title: string;
  message: string;
  danger: boolean;
  action: () => Promise<ActionResult>;
}
/** "3d 4h", "2h 15m", "45s" - rough human duration between two ISO timestamps. */
function formatDuration(fromIso: string, toIso: string): string {
  const secs = Math.max(0, Math.floor((new Date(toIso).getTime() - new Date(fromIso).getTime()) / 1000));
  const d = Math.floor(secs / 86400);
  const h = Math.floor((secs % 86400) / 3600);
  const m = Math.floor((secs % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${secs}s`;
}

export default function EndpointDetailPage() {
  const { id } = useParams<{ id: string }>();

  const [endpoint, setEndpoint] = useState<EndpointResponse | null>(null);
  const [posture, setPosture] = useState<AssessmentResponse | null>(null);
  const [postureHistory, setPostureHistory] = useState<AssessmentResponse[]>([]);
  const [hardware, setHardware] = useState<HardwareHealthResponse | null>(null);
  const [hardwareHistory, setHardwareHistory] = useState<HardwareHealthResponse[]>([]);
  const [jobs, setJobs] = useState<JobResponse[]>([]);
  const [audits, setAudits] = useState<IseActionAudit[]>([]);

  const [actionMsg, setActionMsg] = useState<{ text: string; success: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);
  const [sessions, setSessions] = useState<SessionEvent[]>([]);
  const [diagnostic, setDiagnostic] = useState<DiagnosticResponse | null>(null);
  const [diagnosticHistory, setDiagnosticHistory] = useState<DiagnosticResponse[]>([]);
  const [security, setSecurity] = useState<SecurityIndicatorResponse | null>(null);
  const [securityHistory, setSecurityHistory] = useState<SecurityIndicatorResponse[]>([]);

  const [activeTab, setActiveTab] = useState<
    "posture" | "hardware" | "diagnostics" | "security" | "jobs" | "audit" | "sessions"
  >("posture");

  // Role gates. UI convenience only: the backend @PreAuthorize rules are the real control.
  const mayEnqueue = can("enqueue");
  const mayShare = can("sharePosture");
  const mayRestrict = can("restrict");

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const ep = await api.getEndpoint(id);
      setEndpoint(ep);

      const [
        postureRes,
        histRes,
        hwRes,
        hwHistRes,
        jobsRes,
        auditRes,
        sessionsRes,
        diagRes,
        diagHistRes,
        secRes,
        secHistRes,
      ] = await Promise.allSettled([
        api.latestPostureOrNull(id),
        api.postureHistory(id),
        api.latestHardwareOrNull(id),
        api.hardwareHistory(id),
        api.listJobsForEndpoint(id),
        api.auditActions(id),
        api.sessionHistory(id),
        api.latestDiagnosticOrNull(id),
        api.diagnosticHistory(id),
        api.latestSecurityOrNull(id),
        api.securityHistory(id),
      ]);

      if (postureRes.status === "fulfilled") setPosture(postureRes.value);
      if (histRes.status === "fulfilled") setPostureHistory(histRes.value);
      if (hwRes.status === "fulfilled") setHardware(hwRes.value);
      if (hwHistRes.status === "fulfilled") setHardwareHistory(hwHistRes.value);
      if (jobsRes.status === "fulfilled") setJobs(jobsRes.value);
      if (auditRes.status === "fulfilled") setAudits(auditRes.value);
      if (sessionsRes.status === "fulfilled") setSessions(sessionsRes.value);
      if (diagRes.status === "fulfilled") setDiagnostic(diagRes.value);
      if (diagHistRes.status === "fulfilled") setDiagnosticHistory(diagHistRes.value);
      if (secRes.status === "fulfilled") setSecurity(secRes.value);
      if (secHistRes.status === "fulfilled") setSecurityHistory(secHistRes.value);
    } catch {
      // Endpoint might not exist
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  /** Opens the confirm dialog. Nothing is sent to ISE until the operator confirms. */
  function runAction(
    fn: () => Promise<ActionResult>,
    title: string,
    message: string,
    danger = false
  ) {
    setConfirmState({ title, message, danger, action: fn });
  }

  async function executeConfirmedAction() {
    if (!confirmState) return;
    const { action } = confirmState;
    setConfirmState(null);
    setBusy(true);
    setActionMsg(null);
    try {
      const res = await action();
      setActionMsg({
        text: res.detail || (res.success ? "Operation completed successfully." : "Operation failed."),
        success: res.success,
      });
    } catch (e) {
      setActionMsg({ text: (e as Error).message, success: false });
    } finally {
      // The backend writes an audit row for success AND failure, so always refresh the trail.
      api.auditActions(id).then(setAudits).catch(() => { });
      setBusy(false);
    }
  }

  async function enqueueCheck(type: "POSTURE_CHECK" | "HARDWARE_CHECK" | "DIAGNOSTIC_CHECK" | "SECURITY_CHECK") {
    setBusy(true);
    try {
      await api.enqueueJob(id, type);
      const label = {
        POSTURE_CHECK: "Posture Check",
        HARDWARE_CHECK: "Hardware Check",
        DIAGNOSTIC_CHECK: "Diagnostics",
        SECURITY_CHECK: "Security Scan",
      }[type];
      const durationText = type === "SECURITY_CHECK" ? "usually finishes in about a minute" : "usually finishes in 10 to 30 seconds";
      setActionMsg({
        text: `${label} job enqueued. It ${durationText}. Refresh to see it.`,
        success: true,
      });
      api.listJobsForEndpoint(id).then(setJobs).catch(() => { });
    } catch (e) {
      setActionMsg({ text: `Failed to enqueue job: ${(e as Error).message}`, success: false });
    } finally {
      setBusy(false);
    }
  }

  if (loading && !endpoint) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="flex items-center gap-2 text-sm text-muted">
          <RefreshCw size={16} className="animate-spin text-accent" />
          <span>Loading endpoint data…</span>
        </div>
      </div>
    );
  }

  if (!endpoint) {
    return (
      <div className="panel p-8 text-center">
        <div className="text-base font-semibold text-ink">Endpoint Not Found</div>
        <p className="mt-1 text-xs text-muted">
          The requested device identifier does not exist or has been removed.
        </p>
        <Link
          href="/endpoints"
          className="mt-4 inline-flex items-center gap-1.5 text-xs text-accent hover:underline"
        >
          <ArrowLeft size={13} />
          Return to endpoints directory
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Top back navigation */}
      <div>
        <Link
          href="/endpoints"
          className="inline-flex items-center gap-1.5 text-xs text-muted transition hover:text-ink"
        >
          <ArrowLeft size={13} />
          <span>Back to Endpoints</span>
        </Link>
      </div>

      {/* Main Endpoint Identity Card */}
      <div className="panel p-6">
        <div className="flex flex-col justify-between gap-6 lg:flex-row lg:items-center">
          <div className="flex items-start gap-4">
            <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-2xl bg-accent/15 text-accent shadow-sm">
              <Monitor size={24} />
            </div>

            <div>
              <div className="flex items-center gap-2.5">
                <h1 className="text-xl font-bold tracking-tight text-ink">
                  {endpoint.hostname || "Windows Endpoint"}
                </h1>
                <ConnectionDot connected={endpoint.connected} />
                {posture?.status && <StatusBadge value={posture.status} />}
              </div>

              <div className="mt-1.5 flex flex-wrap items-center gap-3 font-mono text-xs text-muted">
                <span>
                  MAC: <span className="text-ink">{endpoint.macAddress}</span>
                </span>
                <span>&bull;</span>
                <span>
                  IP: <span className="text-ink">{endpoint.ipAddress || "No IP Reported"}</span>
                </span>
                <span>&bull;</span>
                <span className="font-sans text-muted">{endpoint.osName || "Windows"}</span>
              </div>
            </div>
          </div>

          {/* Quick Enqueue */}
          <div className="flex flex-wrap items-center gap-2">
            <button
              disabled={busy || !mayEnqueue}
              title={!mayEnqueue ? DENIED_HINT : undefined}
              onClick={() => enqueueCheck("POSTURE_CHECK")}
              className="flex items-center gap-1.5 rounded-lg border border-accent/40 bg-accent/10 px-3 py-1.5 text-xs font-semibold text-accent transition hover:bg-accent/20 disabled:opacity-50"
            >
              <Play size={12} />
              <span>Check Posture</span>
            </button>

            <button
              disabled={busy || !mayEnqueue}
              title={!mayEnqueue ? DENIED_HINT : undefined}
              onClick={() => enqueueCheck("HARDWARE_CHECK")}
              className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-semibold text-ink transition hover:border-accent/40 hover:text-accent disabled:opacity-50"
            >
              <Cpu size={12} />
              <span>Check Hardware</span>
            </button>

            <button
              disabled={busy || !mayEnqueue}
              title={!mayEnqueue ? DENIED_HINT : undefined}
              onClick={() => enqueueCheck("DIAGNOSTIC_CHECK")}
              className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-semibold text-ink transition hover:border-accent/40 hover:text-accent disabled:opacity-50"
            >
              <Activity size={12} />
              <span>Run Diagnostics</span>
            </button>

            <button
              disabled={busy || !mayEnqueue}
              title={!mayEnqueue ? DENIED_HINT : undefined}
              onClick={() => enqueueCheck("SECURITY_CHECK")}
              className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-semibold text-ink transition hover:border-accent/40 hover:text-accent disabled:opacity-50"
            >
              <ShieldAlert size={12} />
              <span>Run Security Scan</span>
            </button>

            <button
              disabled={busy}
              onClick={loadAll}
              className="rounded-lg border border-border bg-panel p-2 text-muted transition hover:text-ink disabled:opacity-50"
              title="Refresh telemetry"
            >
              <RefreshCw size={13} className={loading ? "animate-spin text-accent" : ""} />
            </button>
          </div>
        </div>

        {/* Cisco ISE Enforcement Action Center */}
        <div className="mt-6 border-t border-border/60 pt-5">
          <div className="flex flex-col justify-between gap-4 md:flex-row md:items-center">
            <div>
              <div className="text-xs font-semibold uppercase tracking-wider text-muted">
                Cisco ISE Adaptive Network Control (ANC)
              </div>
              <div className="text-[11px] text-muted">
                Observe evidence &rarr; Review findings &rarr; Act with ISE policy changes
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <button
                disabled={busy || !mayShare}
                title={!mayShare ? DENIED_HINT : undefined}
                onClick={() =>
                  runAction(
                    () => api.sharePosture(id),
                    "Share Posture with ISE",
                    "This transmits the endpoint's latest posture result to Cisco ISE via the ERS API. ISE's Authorization Policy will decide what to do with it."
                  )
                }
                className="flex items-center gap-1.5 rounded-lg border border-accent/30 bg-accent/10 px-3 py-1.5 text-xs font-medium text-accent transition hover:bg-accent/20 disabled:opacity-50"
              >
                <ShieldCheck size={13} />
                <span>Share Posture to ISE</span>
              </button>

              <button
                disabled={busy || !mayRestrict}
                title={!mayRestrict ? DENIED_HINT : undefined}
                onClick={() =>
                  runAction(
                    () => api.restrict(id),
                    "Restrict Endpoint",
                    "This requests an immediate network quarantine through Cisco ISE ANC. The user's network access may be disrupted right away.",
                    true
                  )
                }
                className="flex items-center gap-1.5 rounded-lg border border-bad/30 bg-bad/10 px-3 py-1.5 text-xs font-medium text-bad transition hover:bg-bad/20 disabled:opacity-50"
              >
                <ShieldX size={13} />
                <span>Restrict (Quarantine)</span>
              </button>

              <button
                disabled={busy || !mayRestrict}
                title={!mayRestrict ? DENIED_HINT : undefined}
                onClick={() =>
                  runAction(
                    () => api.clearRestriction(id),
                    "Clear Restriction",
                    "This clears any active quarantine restriction on Cisco ISE for this endpoint."
                  )
                }
                className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:bg-ink/[0.04] disabled:opacity-50"
              >
                <CheckCircle2 size={13} className="text-good" />
                <span>Clear Restriction</span>
              </button>
            </div>
          </div>

          {!mayRestrict && (
            <div className="mt-3 text-[11px] text-muted">
              {mayShare
                ? "Your role can share posture but cannot restrict or clear an endpoint."
                : "Your role has read-only access. Sharing, restricting and clearing are disabled."}
            </div>
          )}

          {actionMsg && (
            <div
              className={`mt-4 rounded-lg border p-3 text-xs font-medium ${actionMsg.success
                ? "border-good/30 bg-good/10 text-good"
                : "border-bad/30 bg-bad/10 text-bad"
                }`}
            >
              {actionMsg.text}
            </div>
          )}
        </div>
      </div>

      {/* Navigation Tabs */}
      <div className="flex items-center gap-2 border-b border-border text-xs font-medium">
        {(
          [
            ["posture", "Security Posture"],
            ["hardware", "Hardware Health"],
            ["diagnostics", "Diagnostics"],
            ["security", "Security Indicators"],
            ["sessions", `Sessions (${sessions.length})`],
            ["jobs", `Job Queue (${jobs.length})`],
            ["audit", `ISE Audit Log (${audits.length})`],
          ] as const
        ).map(([tab, label]) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`border-b-2 px-3 py-2.5 transition ${activeTab === tab
              ? "border-accent font-semibold text-accent"
              : "border-transparent text-muted hover:text-ink"
              }`}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Tab 1: Posture Assessment */}
      {activeTab === "posture" && (
        <div className="space-y-6">
          {!posture ? (
            <div className="panel p-8 text-center text-xs text-muted">
              No posture check has completed for this device yet. Click &ldquo;Check Posture&rdquo; above
              to dispatch an agentless CIM/WinRM scan.
            </div>
          ) : (
            <>
              <div className="panel p-5">
                <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-center">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-ink">Assessment Result</span>
                      <StatusBadge value={posture.status} />
                    </div>
                    <div className="mt-1 text-xs text-muted">
                      Executed: {new Date(posture.completedAt || posture.startedAt).toLocaleString()}
                    </div>
                  </div>

                  {posture.detail && (
                    <div className="rounded-lg bg-base px-3 py-1.5 font-mono text-xs text-muted">
                      {posture.detail}
                    </div>
                  )}
                </div>

                <div className="mt-5 grid grid-cols-1 gap-4 md:grid-cols-3">
                  {posture.checks.map((c) => {
                    const isFirewall = c.checkType === "FIREWALL";
                    const isPorts = c.checkType === "OPEN_PORTS";
                    const isApps = c.checkType === "APPLICATIONS";

                    return (
                      <div
                        key={c.id}
                        className="rounded-xl border border-border/80 bg-base/50 p-4 transition hover:border-accent/40"
                      >
                        <div className="flex items-center justify-between">
                          <span className="text-xs font-bold text-ink">
                            {isFirewall
                              ? "Windows Firewall"
                              : isPorts
                                ? "Listening Ports"
                                : isApps
                                  ? "Application Control"
                                  : c.checkType}
                          </span>
                          <StatusBadge value={c.status} />
                        </div>

                        <div className="mt-3 text-xs text-muted">
                          {c.details?.summary ? (
                            <div className="leading-relaxed">{String(c.details.summary)}</div>
                          ) : (
                            <div className="italic">No extra details captured.</div>
                          )}
                        </div>

                        {isPorts && c.details && (
                          <div className="mt-3 space-y-1 border-t border-border/60 pt-2 font-mono text-[11px]">
                            {Array.isArray((c.details as any).openPorts) && (
                              <div className="text-good">
                                Open: {(c.details as any).openPorts.join(", ") || "None"}
                              </div>
                            )}
                            {Array.isArray((c.details as any).blockedPorts) && (
                              <div className="text-bad">
                                Blocked: {(c.details as any).blockedPorts.join(", ") || "None"}
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>

              {postureHistory.length > 1 && (
                <div className="panel p-5">
                  <div className="mb-4 text-xs font-semibold uppercase tracking-wider text-muted">
                    Assessment History ({postureHistory.length} runs)
                  </div>
                  <div className="divide-y divide-border/40 text-xs">
                    {postureHistory.map((h) => (
                      <div
                        key={h.id}
                        className="flex items-center justify-between py-2.5 first:pt-0 last:pb-0"
                      >
                        <div className="flex items-center gap-2.5">
                          <StatusBadge value={h.status} />
                          <span className="text-muted">
                            {new Date(h.completedAt || h.startedAt).toLocaleString()}
                          </span>
                        </div>
                        <span className="font-mono text-[11px] text-muted">
                          {h.checks.length} checks evaluated
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Tab 2: Hardware Health */}
      {activeTab === "hardware" && (
        <div className="space-y-6">
          {!hardware ? (
            <div className="panel p-8 text-center text-xs text-muted">
              No hardware report recorded yet. Click &ldquo;Check Hardware&rdquo; above to dispatch the
              hardware agent.
            </div>
          ) : !hardware.succeeded ? (
            /* Never had a successful run: only the failure is available. */
            <div className="panel p-5">
              <div className="flex items-center gap-2">
                <span className="text-sm font-semibold text-ink">Hardware check failed</span>
                <StatusBadge value="FAILED" />
              </div>
              <div className="mt-1 text-xs text-muted">
                Attempted: {new Date(hardware.collectedAt).toLocaleString()}
              </div>
              <div className="mt-3 break-words rounded-lg bg-base px-3 py-2 font-mono text-xs text-bad">
                {hardware.errorMessage ?? "Unknown error"}
              </div>
              <div className="mt-3 text-xs text-muted">
                No successful hardware run exists for this device yet, so there are no scores to show.
              </div>
            </div>
          ) : (
            <>
              {hardware.lastAttemptFailedAt && (
                <div className="rounded-xl border border-warn/30 bg-warn/10 p-4 text-xs">
                  <div className="flex items-center gap-2 font-semibold text-warn">
                    <AlertTriangle size={14} />
                    <span>
                      Latest hardware check failed on{" "}
                      {new Date(hardware.lastAttemptFailedAt).toLocaleString()}
                    </span>
                  </div>
                  <div className="mt-1 text-muted">
                    The scores below are from the last successful run (
                    {new Date(hardware.collectedAt).toLocaleString()}).
                  </div>
                  <div className="mt-2 break-words rounded-lg bg-base px-3 py-2 font-mono text-[11px] text-warn">
                    {hardware.lastAttemptError ?? "Unknown error"}
                  </div>
                </div>
              )}

              <div className="panel p-5">
                <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-ink">Hardware Score</span>
                      {hardware.overallBand && <StatusBadge value={hardware.overallBand} />}
                    </div>
                    <div className="mt-1 text-xs text-muted">
                      Telemetry collected: {new Date(hardware.collectedAt).toLocaleString()}
                    </div>
                  </div>

                  <div className="flex items-baseline gap-1">
                    <span className="text-3xl font-extrabold text-ink">{hardware.overallScore ?? "—"}</span>
                    <span className="text-sm font-semibold text-muted">/ 100</span>
                  </div>
                </div>

                <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <ScoreCard label="CPU Health" value={hardware.cpuScore} icon={Cpu} />
                  <ScoreCard label="Memory Health" value={hardware.memoryScore} icon={Server} />
                  <ScoreCard label="Storage Health" value={hardware.storageScore} icon={HardDrive} />
                  <ScoreCard label="Battery Health" value={hardware.batteryScore} icon={Battery} />
                </div>
              </div>

              {/* Hardware Health Trend Chart (V4) */}
              {(() => {
                const validHardwareRuns = hardwareHistory
                  .filter((r) => r.succeeded && r.overallScore != null)
                  .sort((a, b) => new Date(a.collectedAt).getTime() - new Date(b.collectedAt).getTime());

                const hardwareLabels = validHardwareRuns.map((r) =>
                  new Date(r.collectedAt).toLocaleDateString(undefined, { month: "short", day: "numeric" })
                );

                const hasBattery = validHardwareRuns.some((r) => r.batteryScore != null);

                const hardwareSeries: ChartSeries[] = [
                  {
                    name: "Overall",
                    color: "rgb(var(--color-accent))",
                    values: validHardwareRuns.map((r) => r.overallScore),
                  },
                  {
                    name: "CPU",
                    color: "#10b981",
                    values: validHardwareRuns.map((r) => r.cpuScore),
                  },
                  {
                    name: "Memory",
                    color: "#8b5cf6",
                    values: validHardwareRuns.map((r) => r.memoryScore),
                  },
                  {
                    name: "Storage",
                    color: "#f59e0b",
                    values: validHardwareRuns.map((r) => r.storageScore),
                  },
                ];

                if (hasBattery) {
                  hardwareSeries.push({
                    name: "Battery",
                    color: "#06b6d4",
                    values: validHardwareRuns.map((r) => r.batteryScore),
                  });
                }

                return (
                  <div className="panel p-5">
                    <div className="mb-4 flex items-center justify-between">
                      <div className="text-xs font-semibold uppercase tracking-wider text-muted">
                        Hardware Health Trend
                      </div>
                      {validHardwareRuns.length >= 2 && (
                        <span className="text-xs text-muted">
                          {validHardwareRuns.length} recorded run{validHardwareRuns.length === 1 ? "" : "s"}
                        </span>
                      )}
                    </div>
                    <TrendChart
                      labels={hardwareLabels}
                      series={hardwareSeries}
                      height={160}
                      emptyMessage="Fewer than 2 successful hardware checks recorded. At least 2 points are required to render trend charts."
                    />
                  </div>
                );
              })()}

              <div className="panel p-5">
                <div className="mb-4 text-xs font-semibold uppercase tracking-wider text-muted">
                  System Specifications
                </div>
                <div className="grid grid-cols-2 gap-4 font-mono text-xs sm:grid-cols-5">
                  <div className="rounded-lg bg-base p-3">
                    <div className="font-sans text-muted">Manufacturer</div>
                    <div className="mt-1 font-semibold text-ink">{hardware.manufacturer || "—"}</div>
                  </div>
                  <div className="rounded-lg bg-base p-3">
                    <div className="font-sans text-muted">Model</div>
                    <div className="mt-1 font-semibold text-ink">{hardware.model || "—"}</div>
                  </div>
                  <div className="rounded-lg bg-base p-3">
                    <div className="font-sans text-muted">Serial Number</div>
                    <div className="mt-1 font-semibold text-ink">{hardware.serialNumber || "—"}</div>
                  </div>
                  <div className="rounded-lg bg-base p-3">
                    <div className="font-sans text-muted">BIOS Version</div>
                    <div className="mt-1 font-semibold text-ink">{hardware.biosVersion || "—"}</div>
                  </div>
                  <div className="rounded-lg bg-base p-3">
                    <div className="font-sans text-muted">Warranty</div>
                    <div className="mt-1 font-semibold text-ink">
                      {hardware.warrantyStatus ?? "UNKNOWN"}
                      {hardware.warrantyDaysRemaining != null && (
                        <span className="ml-1 font-normal text-muted">({hardware.warrantyDaysRemaining} d)</span>
                      )}
                    </div>
                  </div>
                </div>
              </div>

              {hardware.recommendations.length > 0 && (
                <div className="panel p-5">
                  <div className="mb-4 text-xs font-semibold uppercase tracking-wider text-muted">
                    Maintenance Recommendations ({hardware.recommendations.length})
                  </div>
                  <div className="space-y-2">
                    {hardware.recommendations.map((r, i) => (
                      <div
                        key={i}
                        className="flex items-start gap-2.5 rounded-lg border border-border/70 bg-base/40 p-3 text-xs"
                      >
                        <span
                          className={`rounded px-1.5 py-0.5 text-[10px] font-bold ${r.priority === "HIGH"
                            ? "bg-bad/15 text-bad"
                            : r.priority === "MEDIUM"
                              ? "bg-warn/15 text-warn"
                              : "bg-good/15 text-good"
                            }`}
                        >
                          {r.priority}
                        </span>
                        <div>
                          <span className="font-semibold text-ink">{r.area}:</span>{" "}
                          <span className="text-muted">{r.action}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}
      {/* Tab: Endpoint 360 diagnostics */}
      {activeTab === "diagnostics" && (
        <DiagnosticsTab latest={diagnostic} history={diagnosticHistory} />
      )}

      {/* Tab: Security Indicators */}
      {activeTab === "security" && (
        <SecurityIndicatorsTab latest={security} history={securityHistory} />
      )}

      {/* Tab: Session history (connect / disconnect events from ISE) */}
      {activeTab === "sessions" && (
        <div className="panel overflow-hidden">
          <div className="border-b border-border/60 px-4 py-2.5 text-[11px] text-muted">
            Connection history is tracked independently of posture. Events are newest first.
          </div>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-left text-xs">
              <thead>
                <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                  <th className="px-4 py-2.5">Event</th>
                  <th className="px-4 py-2.5">IP Address</th>
                  <th className="px-4 py-2.5">Timestamp</th>
                  <th className="px-4 py-2.5">Duration</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/40">
                {sessions.length === 0 && (
                  <tr>
                    <td colSpan={4} className="py-8 text-center text-muted">
                      No connect or disconnect events recorded for this device yet.
                    </td>
                  </tr>
                )}
                {sessions.map((s, i) => {
                  const connected = s.eventType === "CONNECTED";
                  // Newest first, so the next (newer) event is at i - 1. It ends this period.
                  const next = i > 0 ? sessions[i - 1] : null;
                  const duration = next
                    ? formatDuration(s.eventAt, next.eventAt)
                    : connected
                      ? "ongoing"
                      : "still offline";
                  return (
                    <tr key={s.id} className="transition hover:bg-ink/[0.02]">
                      <td className="px-4 py-2.5">
                        <span
                          className={`inline-flex items-center gap-1.5 font-semibold ${connected ? "text-good" : "text-muted"
                            }`}
                        >
                          {connected ? <Wifi size={12} /> : <WifiOff size={12} />}
                          {connected ? "Connected" : "Disconnected"}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 font-mono text-muted">{s.ipAddress || "—"}</td>
                      <td className="px-4 py-2.5 text-muted">{new Date(s.eventAt).toLocaleString()}</td>
                      <td className="px-4 py-2.5 text-muted">
                        {connected ? "Session lasted " : "Offline for "}
                        <span className="font-mono text-ink">{duration}</span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Tab 3: Job Queue for Endpoint */}
      {activeTab === "jobs" && (
        <div className="panel overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-left text-xs">
              <thead>
                <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                  <th className="px-4 py-2.5">Job Type</th>
                  <th className="px-4 py-2.5">Status</th>
                  <th className="px-4 py-2.5">Attempts</th>
                  <th className="px-4 py-2.5">Error Message</th>
                  <th className="px-4 py-2.5">Enqueued</th>
                  <th className="px-4 py-2.5">Completed</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/40">
                {jobs.length === 0 && (
                  <tr>
                    <td colSpan={6} className="py-8 text-center text-muted">
                      No jobs have been enqueued for this endpoint yet.
                    </td>
                  </tr>
                )}
                {jobs.map((j) => (
                  <tr key={j.id} className="transition hover:bg-ink/[0.02]">
                    <td className="px-4 py-2.5 font-semibold text-ink">{j.jobType}</td>
                    <td className="px-4 py-2.5"><StatusBadge value={j.status} /></td>
                    <td className="px-4 py-2.5 text-muted">{j.attemptCount} / {j.maxAttempts}</td>
                    <td className="max-w-xs truncate px-4 py-2.5 text-bad">{j.errorMessage || "—"}</td>
                    <td className="px-4 py-2.5 text-muted">{new Date(j.createdAt).toLocaleString()}</td>
                    <td className="px-4 py-2.5 text-muted">
                      {j.completedAt ? new Date(j.completedAt).toLocaleString() : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Tab 4: ISE Audit History */}
      {activeTab === "audit" && (
        <div className="panel overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-left text-xs">
              <thead>
                <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                  <th className="px-4 py-2.5">Action Type</th>
                  <th className="px-4 py-2.5">Result</th>
                  <th className="px-4 py-2.5">Operator</th>
                  <th className="px-4 py-2.5">Details</th>
                  <th className="px-4 py-2.5">Timestamp</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/40">
                {audits.length === 0 && (
                  <tr>
                    <td colSpan={5} className="py-8 text-center text-muted">
                      No administrative ISE actions recorded for this device yet.
                    </td>
                  </tr>
                )}
                {audits.map((a) => (
                  <tr key={a.id} className="transition hover:bg-ink/[0.02]">
                    <td className="px-4 py-2.5 font-semibold text-ink">{a.actionType}</td>
                    <td className="px-4 py-2.5">
                      <StatusBadge value={a.succeeded ? "COMPLIANT" : "ERROR"} />
                    </td>
                    <td className="px-4 py-2.5 text-muted">{a.operator || "System"}</td>
                    <td className="max-w-md truncate px-4 py-2.5 text-muted">{a.detail}</td>
                    <td className="px-4 py-2.5 text-muted">{new Date(a.occurredAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Confirm dialog for Share / Restrict / Clear */}
      <ConfirmDialog
        open={confirmState !== null}
        title={confirmState?.title ?? ""}
        message={confirmState?.message ?? ""}
        danger={confirmState?.danger}
        onConfirm={executeConfirmedAction}
        onCancel={() => setConfirmState(null)}
      />
    </div>
  );
}

function ScoreCard({
  label,
  value,
  icon: Icon,
}: {
  label: string;
  value: number | null;
  icon: any;
}) {
  const isGood = value != null && value >= 80;
  const isWarn = value != null && value >= 60 && value < 80;
  const color =
    value == null ? "text-muted" : isGood ? "text-good" : isWarn ? "text-warn" : "text-bad";

  return (
    <div className="rounded-xl border border-border/80 bg-base/50 p-3.5">
      <div className="flex items-center justify-between text-xs text-muted">
        <span>{label}</span>
        <Icon size={14} />
      </div>
      <div className={`mt-2 font-mono text-2xl font-bold ${color}`}>
        {value != null ? value : "—"}
        {value != null && <span className="text-xs text-muted">/100</span>}
      </div>
    </div>
  );
}

type Probe = Record<string, unknown> | null | undefined;

function ProbeCard({ title, ok, lines }: { title: string; ok: boolean | null; lines: string[] }) {
  const tone = ok === null ? "text-muted" : ok ? "text-good" : "text-bad";
  return (
    <div className="rounded-xl border border-border/80 bg-base/50 p-3.5">
      <div className="flex items-center justify-between text-xs">
        <span className="font-bold text-ink">{title}</span>
        <span className={`font-semibold ${tone}`}>{ok === null ? "Not measured" : ok ? "Pass" : "Fail"}</span>
      </div>
      <div className="mt-2 space-y-0.5 font-mono text-[11px] text-muted">
        {lines.map((l) => (
          <div key={l}>{l}</div>
        ))}
      </div>
    </div>
  );
}

function DiagnosticsTab({
  latest,
  history,
}: {
  latest: DiagnosticResponse | null;
  history: DiagnosticResponse[];
}) {
  if (!latest) {
    return (
      <div className="panel p-8 text-center text-xs text-muted">
        No diagnostics have run for this device yet. Click &ldquo;Run Diagnostics&rdquo; above. The probes run on the
        endpoint over WinRM, so they show what the user&apos;s machine sees.
      </div>
    );
  }

  if (latest.status === "WINRM_UNAVAILABLE" || latest.status === "FAILED") {
    return (
      <div className="panel p-5">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-ink">
            {latest.status === "FAILED" ? "Diagnostics failed" : "Could not probe from the endpoint"}
          </span>
          <StatusBadge value={latest.status} />
        </div>
        <div className="mt-1 text-xs text-muted">Attempted: {new Date(latest.collectedAt).toLocaleString()}</div>
        <div className="mt-3 break-words rounded-lg bg-base px-3 py-2 font-mono text-xs text-warn">
          {latest.errorMessage ?? "No detail recorded"}
        </div>
        {latest.status === "WINRM_UNAVAILABLE" && (
          <p className="mt-3 text-xs text-muted">
            This is not a failed check, just no measurement. Make sure WinRM is enabled on the device, and for a
            workgroup machine reached by IP, add it to this host&apos;s WinRM TrustedHosts.
          </p>
        )}
      </div>
    );
  }

  const r = latest.results as Record<string, Probe>;
  const gw = r.gateway, dns = r.dns, net = r.internet, tcp = r.tcp443;
  const trace = r.traceroute as {
    target?: string;
    completed?: boolean;
    hops?: { hop: number; address: string | null; ms: string | null }[];
  } | null | undefined;
  const num = (v: unknown) => (typeof v === "number" ? v : v == null ? null : Number(v));
  const bool = (p: Probe, k: string) => (p && typeof p[k] === "boolean" ? (p[k] as boolean) : null);
  const ms = (v: unknown) => (num(v) == null ? "—" : `${Math.round(num(v) as number)} ms`);

  return (
    <div className="space-y-6">
      <div className="panel p-5">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold text-ink">Network Experience</span>
              {latest.band && <StatusBadge value={latest.band} />}
            </div>
            <div className="mt-1 text-xs text-muted">Measured: {new Date(latest.collectedAt).toLocaleString()}</div>
          </div>
          <div className="flex items-baseline gap-1">
            <span className="text-3xl font-extrabold text-ink">{latest.score ?? "—"}</span>
            <span className="text-sm font-semibold text-muted">/ 100</span>
          </div>
        </div>

        <div className="mt-5 grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
          <ProbeCard
            title="Gateway ping"
            ok={bool(gw, "reachable")}
            lines={[
              `${(gw?.address as string) ?? "no gateway found"}`,
              `avg ${ms(gw?.avgMs)}`,
              `loss ${num(gw?.lossPct) ?? "—"}%`,
            ]}
          />
          <ProbeCard
            title="DNS lookup"
            ok={bool(dns, "resolved")}
            lines={[
              `${(dns?.target as string) ?? "—"}`,
              `took ${ms(dns?.ms)}`,
              ...(dns?.error ? [String(dns.error)] : []),
            ]}
          />
          <ProbeCard
            title="Internet ping"
            ok={bool(net, "reachable")}
            lines={[
              `${(net?.address as string) ?? "—"}`,
              `avg ${ms(net?.avgMs)}`,
              `loss ${num(net?.lossPct) ?? "—"}%`,
            ]}
          />
          <ProbeCard
            title="TCP 443"
            ok={bool(tcp, "connected")}
            lines={[`${(tcp?.target as string) ?? "—"}:443`, `connect ${ms(tcp?.ms)}`]}
          />
        </div>
      </div>

      <div className="panel p-5">
        <div className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted">Why points were lost</div>
        {!latest.deductions || latest.deductions.length === 0 ? (
          <div className="text-xs text-good">No deductions. Every measured probe was healthy.</div>
        ) : (
          <div className="space-y-2">
            {latest.deductions.map((d, i) => (
              <div key={i} className="flex items-start gap-2.5 rounded-lg border border-border/70 bg-base/40 p-3 text-xs">
                <span className="rounded bg-bad/15 px-1.5 py-0.5 font-mono text-[10px] font-bold text-bad">
                  -{d.points}
                </span>
                <div>
                  <span className="font-semibold text-ink">{d.check}:</span>{" "}
                  <span className="text-muted">{d.reason}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="panel p-5">
        <div className="mb-3 flex items-center justify-between">
          <div className="text-xs font-semibold uppercase tracking-wider text-muted">
            Traceroute to {trace?.target ?? "internet"}
          </div>
          <span className="text-[11px] text-muted">{trace?.completed ? "Reached target" : "Did not complete"}</span>
        </div>
        {!trace?.hops || trace.hops.length === 0 ? (
          <div className="text-xs text-muted">No hops recorded.</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-left font-mono text-xs">
              <thead>
                <tr className="border-b border-border text-[11px] text-muted">
                  <th className="py-1.5 pr-4">Hop</th>
                  <th className="py-1.5 pr-4">Address</th>
                  <th className="py-1.5">Time</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/40">
                {trace.hops.map((h) => (
                  <tr key={h.hop}>
                    <td className="py-1.5 pr-4 text-muted">{h.hop}</td>
                    <td className="py-1.5 pr-4 text-ink">{h.address ?? "* (no reply)"}</td>
                    <td className="py-1.5 text-muted">{h.ms ? `${h.ms} ms` : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {history.length > 1 && (
        <div className="panel p-5">
          <div className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted">
            Run history ({history.length})
          </div>
          <div className="divide-y divide-border/40 text-xs">
            {history.slice(0, 15).map((h) => (
              <div key={h.id} className="flex items-center justify-between py-2.5 first:pt-0 last:pb-0">
                <div className="flex items-center gap-2.5">
                  <StatusBadge value={h.status === "OK" && h.band ? h.band : h.status} />
                  <span className="text-muted">{new Date(h.collectedAt).toLocaleString()}</span>
                </div>
                <span className="font-mono text-[11px] text-muted">
                  {h.score != null ? `${h.score}/100` : "no score"}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}