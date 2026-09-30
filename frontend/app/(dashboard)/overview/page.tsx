"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import {
  Monitor,
  ShieldCheck,
  AlertTriangle,
  Flame,
  Radio,
  RefreshCw,
  Play,
  Download,
  ArrowRight,
  ExternalLink,
  ShieldAlert,
  ShieldX,
  User,
} from "lucide-react";
import {
  api,
  EndpointResponse,
  AssessmentStatus,
  IseActionAudit,
  DashboardSummary,
  TrendPoint,
} from "@/lib/api";
import { getCurrentUser } from "@/lib/auth";
import { can, DENIED_HINT } from "@/lib/permissions";
import { useCachedFetch } from "@/lib/useCachedFetch";
import { useIseStatus } from "@/lib/IseStatusContext";
import { datedFilename, downloadCsv } from "@/lib/csv";
import { RingGauge } from "@/components/dashboard/RingGauge";
import { StatusDonut } from "@/components/dashboard/StatusDonut";
import { StatCard } from "@/components/dashboard/StatCard";
import { TrendChart } from "@/components/dashboard/TrendChart";
import { RiskList, RiskItem } from "@/components/dashboard/RiskList";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ConnectionDot } from "@/components/ui/ConnectionDot";

interface EndpointRow extends EndpointResponse {
  status?: AssessmentStatus;
}

type Band = "healthy" | "atRisk" | "critical" | "unassessed";

function bandFor(status?: AssessmentStatus): Band {
  if (status === "COMPLIANT") return "healthy";
  if (status === "NON_COMPLIANT") return "atRisk";
  if (status === "ERROR") return "critical";
  return "unassessed"; // never checked: not counted as compliant
}

/** "ATTRIBUTE" -> "Attribute" */
function titleCase(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}

function SectionHeader({
  title,
  hint,
  action,
}: {
  title: string;
  hint?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="mb-5 flex items-start justify-between gap-4">
      <div>
        <h2 className="text-sm font-semibold text-ink">{title}</h2>
        {hint && <p className="mt-1 text-xs text-muted">{hint}</p>}
      </div>
      {action}
    </div>
  );
}

interface OverviewData {
  rows: EndpointRow[];
  audit: IseActionAudit[];
  summary: DashboardSummary | null;
  trend: TrendPoint[];
}

async function fetchOverview(): Promise<OverviewData> {
  // Per-row status still comes from the fleet call; KPI numbers and the trend come from the dashboard APIs.
  const [endpoints, latest, summary, trend] = await Promise.all([
    api.listEndpoints(),
    api.latestPostureAll(),
    api.dashboardSummary().catch(() => null),
    api.dashboardTrend(7).catch(() => [] as TrendPoint[]),
  ]);
  const byEndpoint = new Map(latest.map((a) => [a.endpointId, a]));
  const rows: EndpointRow[] = endpoints.map((e) => ({ ...e, status: byEndpoint.get(e.id)?.status }));

  let audit: IseActionAudit[] = [];
  try {
    audit = (await api.auditActions()).slice(0, 8);
  } catch {
    /* audit table is optional */
  }
  return { rows, audit, summary, trend };
}

export default function OverviewPage() {
  const ise = useIseStatus();
  const iseDown = ise?.reachable === false;

  // UI convenience only: the backend @PreAuthorize rule is the real control.
  const mayEnqueue = can("enqueue");

  const { data, refreshing, error, reload } = useCachedFetch("overview", fetchOverview, {
    ttlMs: 15000,
    pollMs: 20000,
  });
  const rows = data?.rows ?? null;
  const audit = data?.audit ?? [];
  const summary = data?.summary ?? null;

  const [searchFilter, setSearchFilter] = useState("");
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const stats = useMemo(() => {
    const list = rows ?? [];
    // Posture numbers cover CONNECTED devices only. The backend summary already
    // does this; the fallback below mirrors it if the summary call failed.
    const connectedList = list.filter((r) => r.connected);
    const healthy = summary
      ? summary.compliant
      : connectedList.filter((r) => bandFor(r.status) === "healthy").length;
    const atRisk = summary
      ? summary.nonCompliant
      : connectedList.filter((r) => bandFor(r.status) === "atRisk").length;
    const critical = summary
      ? summary.error
      : connectedList.filter((r) => bandFor(r.status) === "critical").length;
    const assessed = healthy + atRisk + critical;
    // Stored flag: while ISE is down this is the last known state (ConnectionDot labels it).
    const connected = connectedList.length;
    const total = summary ? summary.total : list.length;
    const score = assessed === 0 ? null : Math.round((healthy / assessed) * 100);
    const stale = summary ? summary.stale : 0;
    const unassessed = summary
      ? summary.unassessed
      : connectedList.filter((r) => bandFor(r.status) === "unassessed").length;
    return { healthy, atRisk, critical, assessed, connected, total, score, stale, unassessed };
  }, [rows, summary]);

  const risks: RiskItem[] = useMemo(
    () =>
      (rows ?? [])
        .filter((r) => r.connected && ["atRisk", "critical"].includes(bandFor(r.status)))
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

  const liveRows = useMemo(() => {
    const live = (rows ?? []).filter((r) => r.connected);
    const q = searchFilter.trim().toLowerCase();
    if (!q) return live;
    return live.filter(
      (r) =>
        r.macAddress.toLowerCase().includes(q) ||
        r.hostname?.toLowerCase().includes(q) ||
        r.ipAddress?.toLowerCase().includes(q)
    );
  }, [rows, searchFilter]);

  const nameById = useMemo(
    () => new Map((rows ?? []).map((r) => [r.id, r.hostname ?? r.macAddress])),
    [rows]
  );

  const offlineCount = stats.total - stats.connected;

  // Read from the backend so these labels can never disagree with application.yml.
  const pollLabel = ise?.pollIntervalSeconds != null ? `${ise.pollIntervalSeconds}s` : "—";
  const modeLabel = ise?.enforcementMode ? titleCase(ise.enforcementMode) : "—";

  async function triggerScanAll() {
    if (!mayEnqueue) return;
    // Agents talk to endpoints directly, so scanning works even while the ISE poll is down.
    const targets = (rows ?? []).filter((r) => r.connected);
    if (targets.length === 0) {
      setActionNotice("No connected devices to scan right now.");
      setTimeout(() => setActionNotice(null), 4000);
      return;
    }
    setActionNotice("Queuing posture checks for connected endpoints…");
    const results = await Promise.allSettled(targets.map((r) => api.enqueueJob(r.id, "POSTURE_CHECK")));
    const queued = results.filter((x) => x.status === "fulfilled").length;
    setActionNotice(`Queued ${queued} posture checks. Follow progress in Assessment Queue.`);
    reload();
    setTimeout(() => setActionNotice(null), 5000);
  }

  function exportCsv() {
    downloadCsv(
      datedFilename("live-endpoints"),
      ["Hostname", "MAC Address", "IP Address", "Operating System", "Posture Status", "Last Seen (UTC)"],
      liveRows.map((r) => [
        r.hostname,
        r.macAddress,
        r.ipAddress,
        r.osName,
        r.status ?? "UNASSESSED",
        r.lastSeenAt,
      ])
    );
  }

  const user = getCurrentUser();
  const hour = new Date().getHours();
  const greeting = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";

  return (
    <div className="mx-auto max-w-[1400px] space-y-8">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-ink">
              {greeting}
              {user?.username ? `, ${user.username}` : ""}
            </h1>
            <span className="rounded-full bg-accent/15 px-2.5 py-0.5 text-xs font-semibold text-accent">
              Live operations
            </span>
          </div>
          <p className="mt-1.5 text-sm text-muted">
            Continuous posture assessment and Cisco ISE session monitoring across your connected fleet.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={exportCsv}
            disabled={liveRows.length === 0}
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3.5 py-2 text-xs font-medium text-ink transition hover:border-accent/40 disabled:opacity-50"
          >
            <Download size={13} className="text-muted" />
            <span>Export CSV</span>
          </button>
          <button
            onClick={reload}
            disabled={refreshing}
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3.5 py-2 text-xs font-medium text-ink transition hover:border-accent/40 disabled:opacity-50"
          >
            <RefreshCw size={13} className={refreshing ? "animate-spin text-accent" : "text-muted"} />
            <span>Refresh</span>
          </button>
          <button
            onClick={triggerScanAll}
            disabled={!mayEnqueue}
            title={!mayEnqueue ? DENIED_HINT : undefined}
            className="flex items-center gap-1.5 rounded-lg bg-accent px-3.5 py-2 text-xs font-semibold text-base transition hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <Play size={13} />
            <span>Scan connected endpoints</span>
          </button>
        </div>
      </div>

      {actionNotice && (
        <div className="rounded-lg border border-accent/30 bg-accent/10 px-4 py-2.5 text-xs font-medium text-accent">
          {actionNotice}
        </div>
      )}

      {/* ISE status card */}
      <div
        className={`panel flex flex-col justify-between gap-4 p-5 sm:flex-row sm:items-center ${
          iseDown ? "border-warn/40 bg-warn/5" : "bg-panel2/60"
        }`}
      >
        <div className="flex items-center gap-4">
          <div
            className={`relative flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl ${
              iseDown ? "bg-warn/15 text-warn" : "bg-good/15 text-good"
            }`}
          >
            <Radio size={18} />
            <span
              className={`absolute -bottom-0.5 -right-0.5 h-2 w-2 rounded-full ${iseDown ? "bg-warn" : "bg-good"}`}
            />
          </div>
          <div>
            <div className="text-sm font-semibold text-ink">
              {iseDown ? "Cisco ISE integration unreachable" : "Cisco ISE integration active"}
            </div>
            <div className="text-xs text-muted">
              {stats.connected} of {stats.total} devices{" "}
              {iseDown ? "were connected at the last successful poll" : "have a live session"}.
              {!iseDown && ise?.pollIntervalSeconds != null && ` Sessions refresh every ${ise.pollIntervalSeconds} seconds.`}
              {stats.stale > 0 && (
                <>
                  {" "}
                  {stats.stale} result{stats.stale > 1 ? "s are" : " is"} stale.
                </>
              )}
            </div>
            {iseDown && ise?.lastError && (
              <div className="mt-1 font-mono text-[11px] text-warn">{ise.lastError}</div>
            )}
          </div>
        </div>
        <div className="flex items-center gap-5 text-xs text-muted">
          <div>
            Poll frequency <span className="ml-1 font-mono font-semibold text-ink">{pollLabel}</span>
          </div>
          <div className="h-4 w-px bg-border" />
          <div>
            Enforcement mode <span className="ml-1 font-mono font-semibold text-accent">{modeLabel}</span>
          </div>
        </div>
      </div>

      {/* 1. Four KPI cards */}
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          icon={Monitor}
          label="Compliance index"
          value={stats.score === null ? "—" : `${stats.score}%`}
          tone={stats.score === null ? "accent" : stats.score >= 80 ? "good" : stats.score >= 60 ? "warn" : "bad"}
          caption={
            stats.score === null
              ? "No connected device assessed yet"
              : stats.score >= 80
              ? "Connected fleet is in good shape"
              : "Action required"
          }
        />
        <StatCard
          icon={ShieldCheck}
          label="Compliant"
          value={stats.healthy}
          tone="good"
          caption={`of ${stats.assessed} connected assessed${
            stats.unassessed > 0 ? ` · ${stats.unassessed} not yet assessed` : ""
          }`}
        />
        <StatCard icon={AlertTriangle} label="At risk" value={stats.atRisk} tone="warn" caption="Policy not met" />
        <StatCard icon={Flame} label="Critical" value={stats.critical} tone="bad" caption="Check failed to run" />
      </div>

      {/* 2. Visualisations, side by side */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <section className="panel p-6">
          <SectionHeader
            title="Fleet posture index"
            hint="Share of connected, assessed devices that pass every check."
          />
          <div className="flex justify-center py-4">
            {stats.score === null ? (
              <div className="py-10 text-xs text-muted">No assessed connected devices yet.</div>
            ) : (
              <RingGauge value={stats.score} />
            )}
          </div>
        </section>

        <section className="panel p-6">
          <SectionHeader title="Posture distribution" hint="Latest result for each connected device." />
          <div className="flex justify-center py-4">
            <StatusDonut healthy={stats.healthy} atRisk={stats.atRisk} critical={stats.critical} size={160} />
          </div>
        </section>
      </div>

      {/* 2b. Trend */}
      <section className="panel p-6">
        <SectionHeader
          title="Compliance trend (7 days)"
          hint="Daily share of assessed devices whose latest result was compliant."
        />
        <TrendChart data={data?.trend ?? []} />
      </section>

      {/* 3. Live endpoints */}
      <section className="panel p-6">
        <SectionHeader
          title={`${iseDown ? "Last known connected endpoints" : "Live endpoints"} (${stats.connected})`}
          hint={
            iseDown
              ? "ISE is unreachable, so this is the state from the last successful poll."
              : "Devices with an active ISE session right now."
          }
          action={
            <div className="flex items-center gap-4">
              <input
                type="text"
                value={searchFilter}
                onChange={(e) => setSearchFilter(e.target.value)}
                placeholder="Filter live devices…"
                className="w-52 rounded-lg border border-border bg-base px-3 py-1.5 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
              />
              <Link
                href="/endpoints"
                className="flex items-center gap-1 whitespace-nowrap text-xs font-semibold text-accent hover:underline"
              >
                <span>Full directory</span>
                <ArrowRight size={13} />
              </Link>
            </div>
          }
        />

        <div className="overflow-x-auto">
          <table className="w-full min-w-[720px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border text-[11px] font-semibold text-muted">
                <th className="py-3 pr-4">Device</th>
                <th className="py-3 pr-4">IP address</th>
                <th className="py-3 pr-4">Operating system</th>
                <th className="py-3 pr-4">Session</th>
                <th className="py-3 pr-4">Posture</th>
                <th className="py-3 pr-4">Last seen</th>
                <th className="py-3 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={7} className="py-10 text-center text-muted">
                    {error
                      ? "Can't reach the backend. Check that it is running on port 8090."
                      : "Loading endpoints…"}
                  </td>
                </tr>
              )}
              {rows !== null && liveRows.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-10 text-center text-muted">
                    {iseDown
                      ? "No devices were connected at the last successful ISE poll."
                      : "No live sessions match. Devices appear here as soon as ISE reports an active session."}
                  </td>
                </tr>
              )}
              {liveRows.slice(0, 8).map((r) => (
                <tr key={r.id} className="transition hover:bg-ink/[0.03]">
                  <td className="py-3.5 pr-4">
                    <Link href={`/endpoints/${r.id}`} className="font-mono font-medium text-accent hover:underline">
                      {r.hostname ?? r.macAddress}
                    </Link>
                    {r.hostname && <div className="mt-0.5 font-mono text-[10px] text-muted">{r.macAddress}</div>}
                  </td>
                  <td className="py-3.5 pr-4 font-mono text-ink">{r.ipAddress ?? "—"}</td>
                  <td className="max-w-[200px] truncate py-3.5 pr-4 text-muted">{r.osName ?? "—"}</td>
                  <td className="py-3.5 pr-4">
                    <ConnectionDot connected={r.connected} />
                  </td>
                  <td className="py-3.5 pr-4">
                    {r.status ? <StatusBadge value={r.status} /> : <span className="text-muted">Not assessed</span>}
                  </td>
                  <td className="py-3.5 pr-4 text-muted">
                    {new Date(r.lastSeenAt).toLocaleString([], {
                      month: "short",
                      day: "numeric",
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </td>
                  <td className="py-3.5 text-right">
                    <Link
                      href={`/endpoints/${r.id}`}
                      className="inline-flex items-center gap-1 rounded border border-border bg-panel px-2.5 py-1 text-[11px] font-medium text-ink transition hover:border-accent/40 hover:text-accent"
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

        {offlineCount > 0 && (
          <div className="mt-5 flex items-center justify-between rounded-lg bg-base/60 px-4 py-3 text-xs text-muted">
            <span>
              {offlineCount} device{offlineCount > 1 ? "s are" : " is"} not connected, so{" "}
              {offlineCount > 1 ? "they are" : "it is"} left out of the posture numbers above. Last known results
              are kept in the directory.
            </span>
            <Link href="/endpoints" className="font-semibold text-accent hover:underline">
              View offline devices
            </Link>
          </div>
        )}
      </section>

      {/* 4. Needs attention */}
      <section className="panel p-6">
        <SectionHeader
          title="Needs immediate attention"
          hint="Connected devices that are non-compliant or failed to check."
          action={
            <Link href="/compliance" className="text-xs font-semibold text-accent hover:underline">
              View all
            </Link>
          }
        />
        <RiskList items={risks} />
      </section>

      {/* 5. Audit log, full width, at the bottom */}
      <section className="panel p-6">
        <SectionHeader
          title="Recent ISE actions"
          hint="Every share, restrict and clear request, including failed attempts."
          action={
            <Link href="/audit" className="text-xs font-semibold text-accent hover:underline">
              Open audit log
            </Link>
          }
        />
        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="px-4 py-3">Action type</th>
                <th className="px-4 py-3">Outcome</th>
                <th className="px-4 py-3">Operator</th>
                <th className="px-4 py-3">Target endpoint</th>
                <th className="px-4 py-3">Execution detail</th>
                <th className="px-4 py-3">Timestamp</th>
                <th className="px-4 py-3 text-right">Inspect</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {audit.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-10 text-center text-muted">
                    No ISE actions yet. Share, restrict and clear requests appear here as they happen.
                  </td>
                </tr>
              )}
              {audit.map((a) => (
                <tr key={a.id} className="transition hover:bg-ink/[0.02]">
                  <td className="px-4 py-3.5 font-semibold text-ink">
                    <span className="flex items-center gap-1.5">
                      {a.actionType === "SHARE_POSTURE" && <ShieldCheck size={14} className="text-accent" />}
                      {a.actionType === "RESTRICT" && <ShieldX size={14} className="text-bad" />}
                      {a.actionType === "CLEAR_RESTRICTION" && <ShieldAlert size={14} className="text-good" />}
                      <span>{a.actionType}</span>
                    </span>
                  </td>
                  <td className="px-4 py-3.5">
                    <StatusBadge value={a.succeeded ? "COMPLIANT" : "ERROR"} />
                  </td>
                  <td className="px-4 py-3.5 text-ink">
                    <span className="flex items-center gap-1.5">
                      <User size={12} className="text-muted" />
                      <span>{a.operator || "System"}</span>
                    </span>
                  </td>
                  <td className="px-4 py-3.5">
                    <Link href={`/endpoints/${a.endpointId}`} className="text-accent hover:underline" title={a.endpointId}>
                      {nameById.get(a.endpointId) ?? `${a.endpointId.substring(0, 8)}…`}
                    </Link>
                  </td>
                  <td className="max-w-sm truncate px-4 py-3.5 text-muted" title={a.detail ?? ""}>
                    {a.detail}
                  </td>
                  <td className="whitespace-nowrap px-4 py-3.5 text-muted">
                    {new Date(a.occurredAt).toLocaleString([], {
                      month: "short",
                      day: "numeric",
                      hour: "2-digit",
                      minute: "2-digit",
                      second: "2-digit",
                    })}
                  </td>
                  <td className="px-4 py-3.5 text-right">
                    <Link
                      href={`/endpoints/${a.endpointId}`}
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
      </section>
    </div>
  );
}