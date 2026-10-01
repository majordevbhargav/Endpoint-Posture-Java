"use client";

import { useEffect, useRef, useState } from "react";
import { api, WarrantyView, WarrantyUploadResult } from "@/lib/api";
import { can } from "@/lib/permissions";

/* ─── Helpers ─────────────────────────────────────────────────────────────── */

function statusBadge(status: WarrantyView["status"]) {
  const map: Record<WarrantyView["status"], string> = {
    COVERED:       "badge-ok",
    EXPIRING_SOON: "badge-warn",
    EXPIRED:       "badge-bad",
  };
  const label: Record<WarrantyView["status"], string> = {
    COVERED:       "Covered",
    EXPIRING_SOON: "Expiring soon",
    EXPIRED:       "Expired",
  };
  return <span className={`badge ${map[status]}`}>{label[status]}</span>;
}

function daysLabel(days: number) {
  if (days < 0) return `Expired ${Math.abs(days)} day${Math.abs(days) !== 1 ? "s" : ""} ago`;
  if (days === 0) return "Expires today";
  return `${days} day${days !== 1 ? "s" : ""} remaining`;
}

/* ─── Page ────────────────────────────────────────────────────────────────── */

export default function WarrantyPage() {
  const [rows,    setRows]    = useState<WarrantyView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);
  const [filter,  setFilter]  = useState("");

  /* upload state */
  const fileRef                   = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadMsg, setUploadMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const isAdmin = can("editPolicy"); // ADMIN has editPolicy; used as an ADMIN guard here

  const load = () => {
    setLoading(true);
    api.listWarranty()
      .then(data => { setRows(data); setLoading(false); })
      .catch(e   => { setError(e.message); setLoading(false); });
  };

  useEffect(() => { load(); }, []);

  /* deduplicate: show only the latest row per serial */
  const dedupedRows = (() => {
    const seen = new Set<string>();
    return rows.filter(r => {
      if (seen.has(r.serialNumber)) return false;
      seen.add(r.serialNumber);
      return true;
    });
  })();

  const filtered = filter.trim()
    ? dedupedRows.filter(r =>
        r.serialNumber.toLowerCase().includes(filter.toLowerCase()) ||
        r.vendor.toLowerCase().includes(filter.toLowerCase()) ||
        (r.productName ?? "").toLowerCase().includes(filter.toLowerCase())
      )
    : dedupedRows;

  const handleUpload = async () => {
    const file = fileRef.current?.files?.[0];
    if (!file) { setUploadMsg({ ok: false, text: "Please choose a CSV file." }); return; }
    setUploading(true);
    setUploadMsg(null);
    try {
      const result: WarrantyUploadResult = await api.uploadWarrantyCsv(file);
      setUploadMsg({
        ok: true,
        text: `Saved ${result.savedCount} record${result.savedCount !== 1 ? "s" : ""}.` +
              (result.rowErrors.length ? ` ${result.rowErrors.length} row(s) skipped.` : ""),
      });
      if (fileRef.current) fileRef.current.value = "";
      load(); // refresh the table
    } catch (e: unknown) {
      setUploadMsg({ ok: false, text: e instanceof Error ? e.message : "Upload failed" });
    } finally {
      setUploading(false);
    }
  };

  const covered       = dedupedRows.filter(r => r.status === "COVERED").length;
  const expiringSoon  = dedupedRows.filter(r => r.status === "EXPIRING_SOON").length;
  const expired       = dedupedRows.filter(r => r.status === "EXPIRED").length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Warranty Records</h1>
        <p className="mt-1 text-sm text-muted">
          Device warranty data loaded from CSV exports. The latest row per serial number is shown.
        </p>
      </div>

      {/* ── Summary cards ── */}
      {!loading && !error && (
        <div className="grid grid-cols-3 gap-4">
          <div className="card space-y-1">
            <p className="text-xs text-muted">Total serials</p>
            <p className="text-2xl font-bold">{dedupedRows.length}</p>
          </div>
          <div className="card space-y-1">
            <p className="text-xs text-muted">Covered</p>
            <p className="text-2xl font-bold text-good">{covered}</p>
          </div>
          <div className="card space-y-1">
            <p className="text-xs text-muted">Expiring soon / Expired</p>
            <p className="text-2xl font-bold text-warn">
              {expiringSoon} <span className="text-bad">/ {expired}</span>
            </p>
          </div>
        </div>
      )}

      {/* ── Upload (ADMIN only) ── */}
      {isAdmin && (
        <div className="card space-y-3">
          <h2 className="text-sm font-semibold">Upload CSV</h2>
          <p className="text-xs text-muted">
            Expected columns:{" "}
            <code className="rounded bg-surface-alt px-1 py-0.5 text-xs">serial_number</code>,{" "}
            <code className="rounded bg-surface-alt px-1 py-0.5 text-xs">vendor</code>,{" "}
            <code className="rounded bg-surface-alt px-1 py-0.5 text-xs">expires_on</code>{" "}
            (yyyy-MM-dd or MM/dd/yyyy), and optionally{" "}
            <code className="rounded bg-surface-alt px-1 py-0.5 text-xs">product_name</code>.
            Existing records for the same serial are preserved; the newest upload wins.
          </p>
          <div className="flex flex-wrap items-center gap-3">
            <input
              id="warranty-file-input"
              ref={fileRef}
              type="file"
              accept=".csv,text/csv"
              className="text-sm file:mr-3 file:cursor-pointer file:rounded file:border-0
                         file:bg-accent file:px-3 file:py-1.5 file:text-xs file:font-medium
                         file:text-white hover:file:opacity-90"
            />
            <button
              id="warranty-upload-btn"
              className="btn btn-primary text-sm"
              onClick={handleUpload}
              disabled={uploading}
            >
              {uploading ? "Uploading…" : "Upload"}
            </button>
          </div>
          {uploadMsg && (
            <p className={`text-sm ${uploadMsg.ok ? "text-good" : "text-bad"}`}>
              {uploadMsg.text}
            </p>
          )}
        </div>
      )}

      {/* ── Table ── */}
      <div className="card space-y-3">
        <div className="flex items-center gap-3">
          <input
            id="warranty-filter"
            type="text"
            placeholder="Filter by serial, vendor or product…"
            className="input flex-1"
            value={filter}
            onChange={e => setFilter(e.target.value)}
          />
          <span className="text-xs text-muted">{filtered.length} records</span>
        </div>

        {loading && <p className="text-sm text-muted">Loading…</p>}
        {error   && <p className="text-sm text-bad">{error}</p>}
        {!loading && !error && filtered.length === 0 && (
          <p className="text-sm text-muted">
            {rows.length === 0
              ? "No warranty records yet. Upload a CSV to get started."
              : "No records match the current filter."}
          </p>
        )}

        {!loading && !error && filtered.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted">
                  <th className="pb-2 pr-4 font-medium">Serial Number</th>
                  <th className="pb-2 pr-4 font-medium">Vendor</th>
                  <th className="pb-2 pr-4 font-medium">Product</th>
                  <th className="pb-2 pr-4 font-medium">Expires</th>
                  <th className="pb-2 pr-4 font-medium">Remaining</th>
                  <th className="pb-2 pr-4 font-medium">Status</th>
                  <th className="pb-2 font-medium">Uploaded by</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map(r => (
                  <tr key={r.id} className="border-b border-border/40 hover:bg-surface-alt/40">
                    <td className="py-2 pr-4 font-mono text-xs">{r.serialNumber}</td>
                    <td className="py-2 pr-4">{r.vendor}</td>
                    <td className="py-2 pr-4 text-muted">{r.productName ?? "—"}</td>
                    <td className="py-2 pr-4 font-mono text-xs">{r.expiresOn}</td>
                    <td className="py-2 pr-4 text-xs">{daysLabel(r.daysRemaining)}</td>
                    <td className="py-2 pr-4">{statusBadge(r.status)}</td>
                    <td className="py-2 text-xs text-muted">{r.uploadedBy}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
