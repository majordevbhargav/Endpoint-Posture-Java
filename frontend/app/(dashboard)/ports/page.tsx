"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Network, Search, RefreshCw, Monitor, ExternalLink, CheckCircle2, XCircle } from "lucide-react";
import { PortRow } from "@/lib/api";
import { apiExtra, PortSummary } from "@/lib/apiExtra";
import { ExportCsvButton, PaginationBar } from "@/components/ui/PaginationBar";
import { datedFilename, downloadCsv } from "@/lib/csv";

// Server-paged (S13): search, the reachability filter and paging all run in the database.
export default function PortsPage() {
  const [rows, setRows] = useState<PortRow[] | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [summary, setSummary] = useState<PortSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  const [reach, setReach] = useState<"ALL" | "REACHABLE" | "BLOCKED">("ALL");

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
      const res = await apiExtra.portsPage({ page: page - 1, size: pageSize, q: debounced, reach });
      setRows(res.items);
      setTotal(res.total);
    } catch (e) {
      setRows((prev) => prev ?? []);
      setError(e instanceof Error ? e.message : "Could not load inventory from the backend.");
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, debounced, reach]);

  const loadSummary = useCallback(async () => {
    setSummary(await apiExtra.portsSummary().catch(() => null));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    loadSummary();
  }, [loadSummary]);

  async function exportCsv() {
    try {
      const size = 200;
      const all: PortRow[] = [];
      for (let p = 0; p < 2000; p++) {
        const res = await apiExtra.portsPage({ page: p, size, q: debounced, reach });
        all.push(...res.items);
        if (res.items.length < size) break;
      }
      downloadCsv(
        datedFilename("listening-ports"),
        ["Port", "Service / Process", "PID", "Reachability", "Hostname", "MAC Address"],
        all.map((r) => [
          r.port,
          r.process,
          r.pid,
          r.reachable == null ? "UNTESTED" : r.reachable ? "REACHABLE" : "BLOCKED",
          r.hostname,
          r.macAddress,
        ]),
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
          <h1 className="text-xl font-bold tracking-tight text-ink">Listening Ports & Reachability</h1>
          <p className="mt-1 text-xs text-muted">
            Network listeners identified and probed for active reachability during endpoint posture checks. Search and
            filters run on the server.
          </p>
        </div>
        <div className="flex items-center gap-2.5">
          <ExportCsvButton onClick={exportCsv} disabled={total === 0} />
          <button
            onClick={() => {
              load();
              loadSummary();
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

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Total Evaluated Ports</span>
            <Network size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{summary?.totalPorts ?? "…"}</div>
          <div className="text-[11px] text-muted">Across all endpoints</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Reachable / Open</span>
            <CheckCircle2 size={16} className="text-good" />
          </div>
          <div className="mt-2 text-2xl font-bold text-good">{summary?.reachable ?? "…"}</div>
          <div className="text-[11px] text-muted">Probes connected successfully</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Blocked / Filtered</span>
            <XCircle size={16} className="text-bad" />
          </div>
          <div className="mt-2 text-2xl font-bold text-bad">{summary?.blocked ?? "…"}</div>
          <div className="text-[11px] text-muted">Firewall or probe blocked</div>
        </div>
        <div className="panel p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Monitored Endpoints</span>
            <Monitor size={16} className="text-accent" />
          </div>
          <div className="mt-2 text-2xl font-bold text-ink">{summary?.evaluatedEndpoints ?? "…"}</div>
          <div className="text-[11px] text-muted">Devices with port scans</div>
        </div>
      </div>

      <div className="panel flex flex-col justify-between gap-3 p-3 sm:flex-row sm:items-center">
        <div className="relative max-w-md flex-1">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Filter by port number, service, or device…"
            className="w-full rounded-lg border border-border bg-base py-1.5 pl-9 pr-3 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
          />
        </div>
        <select
          value={reach}
          onChange={(e) => {
            setReach(e.target.value as "ALL" | "REACHABLE" | "BLOCKED");
            setPage(1);
          }}
          className="rounded-lg border border-border bg-panel px-2.5 py-1.5 text-xs text-ink outline-none focus:border-accent"
        >
          <option value="ALL">All Reachabilities</option>
          <option value="REACHABLE">Reachable / Open Only</option>
          <option value="BLOCKED">Blocked / Filtered Only</option>
        </select>
      </div>

      <div className="panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="px-4 py-3">Port Number</th>
                <th className="px-4 py-3">Service / Process</th>
                <th className="px-4 py-3">PID</th>
                <th className="px-4 py-3">Reachability Verdict</th>
                <th className="px-4 py-3">Device Hostname</th>
                <th className="px-4 py-3">MAC Address</th>
                <th className="px-4 py-3 text-right">Endpoint</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {rows === null && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    Loading port inventory…
                  </td>
                </tr>
              )}
              {rows !== null && rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    No matching listening ports recorded yet. Ports are populated during posture agent runs.
                  </td>
                </tr>
              )}
              {(rows ?? []).map((r, i) => (
                <tr key={`${r.macAddress}-${r.port}-${i}`} className="transition hover:bg-ink/[0.02]">
                  <td className="px-4 py-3">
                    <span className="rounded border border-border bg-base px-2 py-1 font-mono text-xs font-bold text-ink">
                      TCP/{r.port}
                    </span>
                  </td>
                  <td className="px-4 py-3 font-medium text-ink">{r.process || "Active Listener"}</td>
                  <td className="px-4 py-3 font-mono text-muted">{r.pid != null ? String(r.pid) : "—"}</td>
                  <td className="px-4 py-3">
                    {r.reachable == null ? (
                      <span className="text-[11px] text-muted">Untested</span>
                    ) : r.reachable ? (
                      <span className="inline-flex items-center gap-1.5 rounded-full border border-good/25 bg-good/10 px-2.5 py-0.5 text-xs font-medium text-good">
                        <span className="h-1.5 w-1.5 rounded-full bg-good" />
                        Reachable (Probe Connected)
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1.5 rounded-full border border-bad/25 bg-bad/10 px-2.5 py-0.5 text-xs font-medium text-bad">
                        <span className="h-1.5 w-1.5 rounded-full bg-bad" />
                        Blocked / Filtered
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-ink">{r.hostname || "Windows Host"}</td>
                  <td className="px-4 py-3 font-mono text-muted">{r.macAddress}</td>
                  <td className="px-4 py-3 text-right">
                    <Link
                      href={`/endpoints?q=${encodeURIComponent(r.macAddress)}`}
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