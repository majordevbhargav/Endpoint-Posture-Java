"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  CheckCircle2,
  Clock,
  AlertTriangle,
  Search,
  Plus,
  X,
  ExternalLink,
} from "lucide-react";
import { api, JobResponse, JobType, EndpointResponse } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { usePolling } from "@/lib/usePolling";

const time = (iso: string | null | undefined) =>
  iso
    ? new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })
    : "—";

const columns: Column<JobResponse>[] = [
  {
    key: "macAddress",
    header: "Target Device MAC",
    className: "font-mono font-medium text-ink",
    render: (j) => (
      <Link href={`/endpoints/${j.endpointId}`} className="text-accent hover:underline">
        {j.macAddress}
      </Link>
    ),
    csv: (j) => j.macAddress,
  },
  {
    key: "jobType",
    header: "Job Type",
    className: "font-semibold text-ink",
    render: (j) => (j.jobType === "POSTURE_CHECK" ? "Posture Check" : "Hardware Health"),
    csv: (j) => j.jobType,
  },
  {
    key: "status",
    header: "Status",
    render: (j) => <StatusBadge value={j.status} />,
    csv: (j) => j.status,
  },
  {
    key: "attempts",
    header: "Attempts",
    className: "font-mono text-muted",
    render: (j) => `${j.attemptCount} / ${j.maxAttempts}`,
    csv: (j) => `${j.attemptCount}/${j.maxAttempts}`,
  },
  {
    key: "errorMessage",
    header: "Error / Failure Detail",
    className: "max-w-xs truncate text-bad",
    render: (j) =>
      j.errorMessage ? (
        <span title={j.errorMessage}>{j.errorMessage}</span>
      ) : (
        <span className="font-normal text-muted">—</span>
      ),
    csv: (j) => j.errorMessage,
  },
  {
    key: "createdAt",
    header: "Enqueued",
    className: "text-muted",
    render: (j) => time(j.createdAt),
    // CSV gets the full ISO timestamp, not just the time of day shown in the table.
    csv: (j) => j.createdAt,
  },
  {
    key: "completedAt",
    header: "Finished",
    className: "text-muted",
    render: (j) => time(j.completedAt),
    csv: (j) => j.completedAt,
  },
  {
    key: "inspect",
    header: "Inspect",
    headerClassName: "text-right",
    className: "text-right",
    exportable: false,
    render: (j) => (
      <Link
        href={`/endpoints/${j.endpointId}`}
        className="inline-flex items-center gap-1 rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted transition hover:text-accent"
      >
        <span>Device</span>
        <ExternalLink size={10} />
      </Link>
    ),
  },
];

export default function JobsPage() {
  const [jobs, setJobs] = useState<JobResponse[] | null>(null);
  const [endpoints, setEndpoints] = useState<EndpointResponse[]>([]);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [q, setQ] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");
  const [typeFilter, setTypeFilter] = useState<string>("ALL");

  // Enqueue Modal State
  const [modalOpen, setModalOpen] = useState(false);
  const [selectedEndpointId, setSelectedEndpointId] = useState("");
  const [selectedJobType, setSelectedJobType] = useState<JobType>("POSTURE_CHECK");
  const [submitting, setSubmitting] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const loadJobs = async () => {
    try {
      const data = await api.listJobs();
      setJobs(data);
    } catch {
      setJobs((prev) => prev ?? []); // keep the last good list on a failed poll
    }
  };

  useEffect(() => {
    loadJobs();
    api.listEndpoints().then(setEndpoints).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The toggle was previously cosmetic (always 20s). It now really pauses the poll
  // and matches its "4s" label.
  usePolling(loadJobs, 4000, autoRefresh);

  const stats = useMemo(() => {
    const list = jobs ?? [];
    return {
      running: list.filter((j) => j.status === "RUNNING").length,
      queued: list.filter((j) => j.status === "QUEUED").length,
      completed: list.filter((j) => j.status === "COMPLETE").length,
      failed: list.filter((j) => j.status === "FAILED").length,
    };
  }, [jobs]);

  const shown = useMemo(() => {
    if (!jobs) return null;
    return jobs.filter((j) => {
      if (statusFilter !== "ALL" && j.status !== statusFilter) return false;
      if (typeFilter !== "ALL" && j.jobType !== typeFilter) return false;

      if (q.trim()) {
        const query = q.toLowerCase();
        const matches =
          j.macAddress?.toLowerCase().includes(query) ||
          j.jobType?.toLowerCase().includes(query) ||
          j.errorMessage?.toLowerCase().includes(query);
        if (!matches) return false;
      }
      return true;
    });
  }, [jobs, statusFilter, typeFilter, q]);

  async function handleEnqueue(e: React.FormEvent) {
    e.preventDefault();
    if (!selectedEndpointId) {
      setModalError("Please select a target endpoint.");
      return;
    }
    setSubmitting(true);
    setModalError(null);
    try {
      await api.enqueueJob(selectedEndpointId, selectedJobType);
      setModalOpen(false);
      setActionNotice(`Successfully enqueued ${selectedJobType} job.`);
      setTimeout(() => setActionNotice(null), 4000);
      loadJobs();
    } catch (err) {
      setModalError(err instanceof Error ? err.message : "Failed to enqueue job.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Assessment Job Queue</h1>
          <p className="mt-1 text-xs text-muted">
            Live queue of posture and hardware checks processed by background PowerShell agents.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={() => setAutoRefresh(!autoRefresh)}
            className={`flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-medium transition ${
              autoRefresh
                ? "border-accent/30 bg-accent/10 text-accent"
                : "border-border bg-panel text-muted hover:text-ink"
            }`}
          >
            <span className={`h-2 w-2 rounded-full ${autoRefresh ? "animate-pulse bg-accent" : "bg-muted"}`} />
            <span>{autoRefresh ? "Live 4s Sync" : "Sync Paused"}</span>
          </button>

          <button
            onClick={() => {
              setSelectedEndpointId(endpoints[0]?.id || "");
              setModalOpen(true);
            }}
            className="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-base transition hover:bg-accent/90"
          >
            <Plus size={13} />
            <span>Enqueue Job</span>
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
            <span>Running Now</span>
            <div className="flex h-2 w-2 animate-ping rounded-full bg-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-accent">{stats.running}</div>
          <div className="text-[11px] text-muted">Active agent runs</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Queued</span>
            <Clock size={16} className="text-muted" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{stats.queued}</div>
          <div className="text-[11px] text-muted">Awaiting claim</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Completed</span>
            <CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{stats.completed}</div>
          <div className="text-[11px] text-muted">Successful reports submitted</div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Failed Runs</span>
            <AlertTriangle size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{stats.failed}</div>
          <div className="text-[11px] text-muted">CIM/WinRM timeouts & crashes</div>
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
            placeholder="Search by MAC, job type, or error…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
          />
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Statuses</option>
            <option value="RUNNING">Running</option>
            <option value="QUEUED">Queued</option>
            <option value="COMPLETE">Complete</option>
            <option value="FAILED">Failed</option>
          </select>

          <select
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
          >
            <option value="ALL">All Job Types</option>
            <option value="POSTURE_CHECK">Posture Checks</option>
            <option value="HARDWARE_CHECK">Hardware Checks</option>
          </select>
        </div>
      </div>

      {/* Jobs Table (paginated + CSV) */}
      <DataTable<JobResponse>
        rows={shown}
        columns={columns}
        rowKey={(j) => j.id}
        csvFilename="jobs"
        loadingMessage="Loading job queue…"
        emptyMessage="No jobs matching your filter. New jobs are enqueued automatically when ISE reports active sessions."
        minWidth={860}
        toolbarLeft={shown ? `${shown.length} matching job${shown.length === 1 ? "" : "s"}` : undefined}
      />

      {/* Enqueue Modal */}
      {modalOpen && (
        <div className="backdrop-blur-xs fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="w-full max-w-md rounded-2xl border border-border bg-panel p-6 shadow-2xl">
            <div className="flex items-center justify-between border-b border-border/60 pb-3">
              <h2 className="text-sm font-bold text-ink">Enqueue Background Assessment Job</h2>
              <button onClick={() => setModalOpen(false)} className="text-muted hover:text-ink">
                <X size={16} />
              </button>
            </div>

            {modalError && (
              <div className="mt-3 rounded-lg border border-bad/30 bg-bad/10 p-2.5 text-xs text-bad">
                {modalError}
              </div>
            )}

            <form onSubmit={handleEnqueue} className="mt-4 space-y-4">
              <div>
                <label className="mb-1 block text-xs font-semibold text-ink">Target Endpoint</label>
                <select
                  required
                  value={selectedEndpointId}
                  onChange={(e) => setSelectedEndpointId(e.target.value)}
                  className="w-full rounded-lg border border-border bg-base px-3 py-2 text-xs text-ink outline-none focus:border-accent"
                >
                  <option value="">Select an endpoint…</option>
                  {endpoints.map((ep) => (
                    <option key={ep.id} value={ep.id}>
                      {ep.hostname ? `${ep.hostname} (${ep.macAddress})` : ep.macAddress}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="mb-1 block text-xs font-semibold text-ink">Job Type</label>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={() => setSelectedJobType("POSTURE_CHECK")}
                    className={`rounded-lg border p-3 text-left text-xs transition ${
                      selectedJobType === "POSTURE_CHECK"
                        ? "border-accent bg-accent/10 font-semibold text-accent"
                        : "border-border bg-base text-muted hover:text-ink"
                    }`}
                  >
                    <div className="font-bold">Posture Check</div>
                    <div className="text-[10px] text-muted">Firewall, Ports, Apps</div>
                  </button>

                  <button
                    type="button"
                    onClick={() => setSelectedJobType("HARDWARE_CHECK")}
                    className={`rounded-lg border p-3 text-left text-xs transition ${
                      selectedJobType === "HARDWARE_CHECK"
                        ? "border-accent bg-accent/10 font-semibold text-accent"
                        : "border-border bg-base text-muted hover:text-ink"
                    }`}
                  >
                    <div className="font-bold">Hardware Check</div>
                    <div className="text-[10px] text-muted">CPU, Disk, Battery</div>
                  </button>
                </div>
              </div>

              <div className="flex items-center justify-end gap-2 border-t border-border/60 pt-4">
                <button
                  type="button"
                  onClick={() => setModalOpen(false)}
                  className="rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink hover:bg-ink/[0.04]"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="flex items-center gap-1.5 rounded-lg bg-accent px-4 py-1.5 text-xs font-semibold text-base hover:bg-accent/90 disabled:opacity-50"
                >
                  {submitting ? "Enqueueing…" : "Enqueue Job"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}