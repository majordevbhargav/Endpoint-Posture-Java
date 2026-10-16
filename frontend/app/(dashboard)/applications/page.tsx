"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  Boxes,
  Search,
  RefreshCw,
  ShieldCheck,
  AlertTriangle,
  Monitor,
  CheckCircle2,
  Lock,
} from "lucide-react";
import { AppRow, api, AppPolicy } from "@/lib/api";
import { apiExtra, AppSummary } from "@/lib/apiExtra";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ExportCsvButton, PaginationBar } from "@/components/ui/PaginationBar";
import { datedFilename, downloadCsv } from "@/lib/csv";

// Server-paged (S13): search, the status filter and paging all run in the database.
export default function ApplicationsPage() {
  const [rows, setRows] = useState<AppRow[] | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [summary, setSummary] = useState<AppSummary | null>(null);
  const [policy, setPolicy] = useState<AppPolicy | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(q.trim());
      setPage(1);
    }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await apiExtra.applicationsPage({ page: page - 1, size: pageSize, q: debounced, status: statusFilter });
      setRows(res.items);
      setTotal(res.total);
    } catch (e) {
      setRows((prev) => prev ?? []);
      setError(e instanceof Error ? e.message : "Could not load inventory from the backend.");
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, debounced, statusFilter]);

  const loadSide = useCallback(async () => {
    const [s, p] = await Promise.all([
      apiExtra.applicationsSummary().catch(() => null),
      api.policy().catch(() => null), // the banner is optional; the table is not
    ]);
    setSummary(s);
    setPolicy(p);
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    loadSide();
  }, [loadSide]);

  async function exportCsv() {
    // Every row matching the active filters, walked page by page.
    try {
      const size = 200;
      const all: AppRow[] = [];
      for (let p = 0; p < 2000; p++) {
        const res = await apiExtra.applicationsPage({ page: p, size, q: debounced, status: statusFilter });
        all.push(...res.items);
        if (res.items.length < size) break;
      }
      downloadCsv(
        datedFilename("installed-software"),
        ["Application", "Version", "Status", "Publisher", "Hostname", "MAC Address", "Evaluation Details"],
        all.map((r) => [r.name, r.version, r.status ?? "INSTALLED", r.publisher, r.hostname, r.macAddress, r.summary]),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Export failed.");
    }
  }

  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);

  return (
    <div className="space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Application Control & Software</h1>
          <p className="mt-1 text-xs text-muted">
            Installed endpoint software from each device&apos;s latest posture run, evaluated against the active
            required and blocked policy. Search and filters run on the server.
          </p>
        </div>
        <div className="flex items-center gap-2.5">
          <ExportCsvButton onClick={exportCsv} disabled={total === 0} />
          <button
            onClick={() => {
              load();
              loadSide();
            }}
            disabled={loading}
            className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:border-accent/40 disabled:opacity-50"
          >
            <RefreshCw size={13} className={loading ? "animate-spin text-accent" : "text-muted"} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {error && <div className="rounded-lg border border-bad/30 bg-bad/10 p-3 text-xs text-bad">{error}</div>}

      <div className="panel grid grid-cols-1 gap-4 bg-panel2/50 p-4 md:grid-cols-2">
        <div className="flex items-start gap-3">
          <div className="rounded-lg bg-good/15 p-2 text-good">
            <ShieldCheck size={18} />
          </div>
          <div>
            <div className="text-xs font-bold text-ink">Mandatory Required Applications</div>
            <div className="mt-0.5 text-xs text-muted">
              Must be installed on all Windows endpoints:{" "}
              <span className="font-mono font-semibold text-good">
                {policy ? policy.requiredApps.join(", ") || "None" : "…"}
              </span>
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
              Forbidden on enterprise endpoints:{" "}
              <span className="font-mono font-semibold text-bad">
                {policy ? policy.blockedApps.join(", ") || "None" : "…"}
              </span>
            </div>
          </div>
        </div>
        {policy && (
          <div className="text-[11px] text-muted md:col-span-2">
            Policy version <span className="font-mono font-semibold text-ink">v{policy.version}</span>
            {policy.createdBy ? ` · set by ${policy.createdBy}` : ""}
            {" · "}
            <Link href="/policies" className="text-accent hover:underline">
              Manage policy
            </Link>
          </div>
        )}
      </div>

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Evaluated Endpoints</span>
            <Monitor size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{summary?.evaluatedEndpoints ?? "…"}</div>
          <div className="text-[11px] text-muted">With application inventory</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Required Apps Present</span>
            <CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{summary?.compliant ?? "…"}</div>
          <div className="text-[11px] text-muted">Satisfies baseline policy</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Blocked Apps Found</span>
            <AlertTriangle size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{summary?.nonCompliant ?? "…"}</div>
          <div className="text-[11px] text-muted">Policy violations</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Total Installed Apps</span>
            <Boxes size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{summary?.totalApps ?? "…"}</div>
          <div className="text-[11px] text-muted">Across all endpoints</div>
        </div>
      </div>

      <div className="panel flex flex-col justify-between gap-3 p-3 sm:flex-row sm:items-center">
        <div className="relative max-w-md flex-1">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Filter by application name, publisher, device, or MAC…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
          />
        </div>
        <select
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value);
            setPage(1);
          }}
          className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
        >
          <option value="ALL">All Statuses</option>
          <option value="COMPLIANT">Required Present</option>
          <option value="NON_COMPLIANT">Blocked Found</option>
        </select>
      </div>

      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="px-4 py-3">Application</th>
                <th className="px-4 py-3">Version</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Publisher / Source</th>
                <th className="px-4 py-3">Device Hostname</th>
                <th className="px-4 py-3">MAC Address</th>
                <th className="px-4 py-3">Evaluation Details</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    Loading application inventory…
                  </td>
                </tr>
              )}
              {rows !== null && rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    No matching software records found. Inventory appears once posture checks run.
                  </td>
                </tr>
              )}
              {(rows ?? []).map((r, i) => (
                <tr key={`${r.macAddress}-${r.name}-${i}`} className="transition hover:bg-ink/[0.02]">
                  <td className="px-4 py-3 font-semibold text-ink">{r.name}</td>
                  <td className="px-4 py-3 font-mono text-muted">{r.version || "—"}</td>
                  <td className="px-4 py-3">
                    {r.status ? <StatusBadge value={r.status} /> : <span className="text-[11px] text-muted">Installed</span>}
                  </td>
                  <td className="px-4 py-3 text-muted">{r.publisher || "Windows Registry"}</td>
                  <td className="px-4 py-3 text-ink">{r.hostname || "Windows Host"}</td>
                  <td className="px-4 py-3 font-mono text-muted">{r.macAddress}</td>
                  <td className="max-w-xs truncate px-4 py-3 text-muted" title={r.summary ?? ""}>
                    {r.summary || "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {rows !== null && (
          <PaginationBar
            page={page}
            pageCount={pageCount}
            pageSize={pageSize}
            total={total}
            from={from}
            to={to}
            setPage={(p) => setPage(Math.max(1, Math.min(p, pageCount)))}
            setPageSize={(n) => {
              setPageSize(n);
              setPage(1);
            }}
          />
        )}
      </div>
    </div>
  );
}