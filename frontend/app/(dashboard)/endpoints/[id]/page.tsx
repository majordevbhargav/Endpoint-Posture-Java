"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import {
  Monitor,
  ShieldCheck,
  ShieldAlert,
  ShieldX,
  Play,
  Cpu,
  RefreshCw,
  ArrowLeft,
  CheckCircle2,
  AlertTriangle,
  Flame,
  Clock,
  History,
  HardDrive,
  Battery,
  Server,
  Activity,
  Layers,
} from "lucide-react";
import {
  api,
  EndpointResponse,
  AssessmentResponse,
  HardwareHealthResponse,
  JobResponse,
  IseActionAudit,
} from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ConnectionDot } from "@/components/ui/ConnectionDot";

export default function EndpointDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();

  const [endpoint, setEndpoint] = useState<EndpointResponse | null>(null);
  const [posture, setPosture] = useState<AssessmentResponse | null>(null);
  const [postureHistory, setPostureHistory] = useState<AssessmentResponse[]>([]);
  const [hardware, setHardware] = useState<HardwareHealthResponse | null>(null);
  const [jobs, setJobs] = useState<JobResponse[]>([]);
  const [audits, setAudits] = useState<IseActionAudit[]>([]);

  const [activeTab, setActiveTab] = useState<"posture" | "hardware" | "jobs" | "audit">("posture");
  const [actionMsg, setActionMsg] = useState<{ text: string; success: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const ep = await api.getEndpoint(id);
      setEndpoint(ep);

      const [postureRes, histRes, hwRes, jobsRes, auditRes] = await Promise.allSettled([
        api.latestPosture(id),
        api.postureHistory(id),
        api.latestHardware(id),
        api.listJobsForEndpoint(id),
        api.auditActions(id),
      ]);

      if (postureRes.status === "fulfilled") setPosture(postureRes.value);
      if (histRes.status === "fulfilled") setPostureHistory(histRes.value);
      if (hwRes.status === "fulfilled") setHardware(hwRes.value);
      if (jobsRes.status === "fulfilled") setJobs(jobsRes.value);
      if (auditRes.status === "fulfilled") setAudits(auditRes.value);
    } catch {
      // Endpoint might not exist
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  async function runAction(
    fn: () => Promise<{ success: boolean; detail: string }>,
    confirmMsg: string
  ) {
    if (!confirm(confirmMsg)) return;
    setBusy(true);
    setActionMsg(null);
    try {
      const res = await fn();
      setActionMsg({
        text: res.detail || (res.success ? "Operation completed successfully." : "Operation failed."),
        success: res.success,
      });
      // Refresh audit logs
      api.auditActions(id).then(setAudits).catch(() => {});
    } catch (e) {
      setActionMsg({ text: (e as Error).message, success: false });
    } finally {
      setBusy(false);
    }
  }

  async function enqueueCheck(type: "POSTURE_CHECK" | "HARDWARE_CHECK") {
    setBusy(true);
    try {
      await api.enqueueJob(id, type);
      setActionMsg({
        text: `${type === "POSTURE_CHECK" ? "Posture Check" : "Hardware Check"} job enqueued. JobWorker will dispatch momentarily.`,
        success: true,
      });
      api.listJobsForEndpoint(id).then(setJobs).catch(() => {});
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
          className="inline-flex items-center gap-1.5 text-xs text-muted hover:text-ink transition"
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
                <span>MAC: <span className="text-ink">{endpoint.macAddress}</span></span>
                <span>&bull;</span>
                <span>IP: <span className="text-ink">{endpoint.ipAddress || "No IP Reported"}</span></span>
                <span>&bull;</span>
                <span className="font-sans text-muted">{endpoint.osName || "Windows"}</span>
              </div>
            </div>
          </div>

          {/* Quick Enqueue & ISE Action Buttons */}
          <div className="flex flex-wrap items-center gap-2">
            <button
              disabled={busy}
              onClick={() => enqueueCheck("POSTURE_CHECK")}
              className="flex items-center gap-1.5 rounded-lg border border-accent/40 bg-accent/10 px-3 py-1.5 text-xs font-semibold text-accent hover:bg-accent/20 disabled:opacity-50 transition"
            >
              <Play size={12} />
              <span>Check Posture</span>
            </button>

            <button
              disabled={busy}
              onClick={() => enqueueCheck("HARDWARE_CHECK")}
              className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-semibold text-ink hover:border-accent/40 hover:text-accent disabled:opacity-50 transition"
            >
              <Cpu size={12} />
              <span>Check Hardware</span>
            </button>

            <button
              disabled={busy}
              onClick={loadAll}
              className="rounded-lg border border-border bg-panel p-2 text-muted hover:text-ink disabled:opacity-50 transition"
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
                disabled={busy}
                onClick={() =>
                  runAction(
                    () => api.sharePosture(id),
                    "Transmit this endpoint's latest posture result to Cisco ISE via ERS API?"
                  )
                }
                className="flex items-center gap-1.5 rounded-lg border border-accent/30 bg-accent/10 px-3 py-1.5 text-xs font-medium text-accent hover:bg-accent/20 disabled:opacity-50 transition"
              >
                <ShieldCheck size={13} />
                <span>Share Posture to ISE</span>
              </button>

              <button
                disabled={busy}
                onClick={() =>
                  runAction(
                    () => api.restrict(id),
                    "Enforce immediate network quarantine on this endpoint through Cisco ISE ANC?"
                  )
                }
                className="flex items-center gap-1.5 rounded-lg border border-bad/30 bg-bad/10 px-3 py-1.5 text-xs font-medium text-bad hover:bg-bad/20 disabled:opacity-50 transition"
              >
                <ShieldX size={13} />
                <span>Restrict (Quarantine)</span>
              </button>

              <button
                disabled={busy}
                onClick={() =>
                  runAction(
                    () => api.clearRestriction(id),
                    "Clear quarantine restriction on Cisco ISE for this endpoint?"
                  )
                }
                className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink hover:bg-ink/[0.04] disabled:opacity-50 transition"
              >
                <CheckCircle2 size={13} className="text-good" />
                <span>Clear Restriction</span>
              </button>
            </div>
          </div>

          {actionMsg && (
            <div
              className={`mt-4 rounded-lg border p-3 text-xs font-medium ${
                actionMsg.success
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
        <button
          onClick={() => setActiveTab("posture")}
          className={`border-b-2 px-3 py-2.5 transition ${
            activeTab === "posture"
              ? "border-accent text-accent font-semibold"
              : "border-transparent text-muted hover:text-ink"
          }`}
        >
          Security Posture
        </button>

        <button
          onClick={() => setActiveTab("hardware")}
          className={`border-b-2 px-3 py-2.5 transition ${
            activeTab === "hardware"
              ? "border-accent text-accent font-semibold"
              : "border-transparent text-muted hover:text-ink"
          }`}
        >
          Hardware Health
        </button>

        <button
          onClick={() => setActiveTab("jobs")}
          className={`border-b-2 px-3 py-2.5 transition ${
            activeTab === "jobs"
              ? "border-accent text-accent font-semibold"
              : "border-transparent text-muted hover:text-ink"
          }`}
        >
          Job Queue ({jobs.length})
        </button>

        <button
          onClick={() => setActiveTab("audit")}
          className={`border-b-2 px-3 py-2.5 transition ${
            activeTab === "audit"
              ? "border-accent text-accent font-semibold"
              : "border-transparent text-muted hover:text-ink"
          }`}
        >
          ISE Audit Log ({audits.length})
        </button>
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
              {/* Latest Posture Result Card */}
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
                    <div className="rounded-lg bg-base px-3 py-1.5 text-xs text-muted font-mono">
                      {posture.detail}
                    </div>
                  )}
                </div>

                {/* Individual Checks Grid */}
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

                        {/* Extra details for ports */}
                        {isPorts && c.details && (
                          <div className="mt-3 space-y-1 border-t border-border/60 pt-2 text-[11px] font-mono">
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

              {/* Assessment History Timeline */}
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
          ) : (
            <>
              {/* Overall Score Banner */}
              <div className="panel p-5">
                <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-ink">Hardware Score</span>
                      <StatusBadge value={hardware.overallBand} />
                    </div>
                    <div className="mt-1 text-xs text-muted">
                      Telemetry collected: {new Date(hardware.collectedAt).toLocaleString()}
                    </div>
                  </div>

                  <div className="flex items-baseline gap-1">
                    <span className="text-3xl font-extrabold text-ink">{hardware.overallScore}</span>
                    <span className="text-sm font-semibold text-muted">/ 100</span>
                  </div>
                </div>

                {/* Score Cells */}
                <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <ScoreCard label="CPU Health" value={hardware.cpuScore} icon={Cpu} />
                  <ScoreCard label="Memory Health" value={hardware.memoryScore} icon={Server} />
                  <ScoreCard label="Storage Health" value={hardware.storageScore} icon={HardDrive} />
                  <ScoreCard label="Battery Health" value={hardware.batteryScore} icon={Battery} />
                </div>
              </div>

              {/* Hardware Specifications */}
              <div className="panel p-5">
                <div className="mb-4 text-xs font-semibold uppercase tracking-wider text-muted">
                  System Specifications
                </div>
                <div className="grid grid-cols-2 gap-4 text-xs sm:grid-cols-4 font-mono">
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
                </div>
              </div>

              {/* Recommendations */}
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
                          className={`rounded px-1.5 py-0.5 text-[10px] font-bold ${
                            r.priority === "HIGH"
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

      {/* Tab 3: Job Queue for Endpoint */}
      {activeTab === "jobs" && (
        <div className="panel overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-left text-xs">
              <thead>
                <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                  <th className="py-2.5 px-4">Job Type</th>
                  <th className="py-2.5 px-4">Status</th>
                  <th className="py-2.5 px-4">Attempts</th>
                  <th className="py-2.5 px-4">Error Message</th>
                  <th className="py-2.5 px-4">Enqueued</th>
                  <th className="py-2.5 px-4">Completed</th>
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
                    <td className="py-2.5 px-4 font-semibold text-ink">{j.jobType}</td>
                    <td className="py-2.5 px-4"><StatusBadge value={j.status} /></td>
                    <td className="py-2.5 px-4 text-muted">{j.attemptCount} / {j.maxAttempts}</td>
                    <td className="max-w-xs truncate py-2.5 px-4 text-bad">{j.errorMessage || "—"}</td>
                    <td className="py-2.5 px-4 text-muted">{new Date(j.createdAt).toLocaleString()}</td>
                    <td className="py-2.5 px-4 text-muted">
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
                  <th className="py-2.5 px-4">Action Type</th>
                  <th className="py-2.5 px-4">Result</th>
                  <th className="py-2.5 px-4">Operator</th>
                  <th className="py-2.5 px-4">Details</th>
                  <th className="py-2.5 px-4">Timestamp</th>
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
                    <td className="py-2.5 px-4 font-semibold text-ink">{a.actionType}</td>
                    <td className="py-2.5 px-4">
                      <StatusBadge value={a.succeeded ? "COMPLIANT" : "ERROR"} />
                    </td>
                    <td className="py-2.5 px-4 text-muted">{a.operator || "System"}</td>
                    <td className="max-w-md truncate py-2.5 px-4 text-muted">{a.detail}</td>
                    <td className="py-2.5 px-4 text-muted">{new Date(a.occurredAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
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
    value == null
      ? "text-muted"
      : isGood
      ? "text-good"
      : isWarn
      ? "text-warn"
      : "text-bad";

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