"use client";

import { useEffect, useRef, useState } from "react";
import { api, WarrantyView, WarrantyUploadResult } from "@/lib/api";
import { can, DENIED_HINT } from "@/lib/permissions";
import { StatusBadge } from "@/components/ui/StatusBadge";

function daysLabel(days: number) {
  if (days < 0) return `Expired ${Math.abs(days)} day${Math.abs(days) !== 1 ? "s" : ""} ago`;
  if (days === 0) return "Expires today";
  return `${days} day${days !== 1 ? "s" : ""} remaining`;
}

export default function WarrantyPage() {
  const [rows, setRows] = useState<WarrantyView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("");

  const fileRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadMsg, setUploadMsg] = useState<{ ok: boolean; text: string } | null>(null);

  // UI convenience only: the backend @PreAuthorize rule is the real control.
  const mayUpload = can("uploadWarranty");

  const load = () => {
    setLoading(true);
    api
      .listWarranty()
      .then((data) => {
        setRows(data);
        setError(null);
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  // Newest upload first, so keep the first row seen for each serial.
  const dedupedRows = (() => {
    const seen = new Set<string>();
    return rows.filter((r) => {
      if (seen.has(r.serialNumber)) return false;
      seen.add(r.serialNumber);
      return true;
    });
  })();

  const q = filter.trim().toLowerCase();
  const filtered = q
    ? dedupedRows.filter(
        (r) =>
          r.serialNumber.toLowerCase().includes(q) ||
          r.vendor.toLowerCase().includes(q) ||
          (r.productName ?? "").toLowerCase().includes(q)
      )
    : dedupedRows;

  const handleUpload = async () => {
    const file = fileRef.current?.files?.[0];
    if (!file) {
      setUploadMsg({ ok: false, text: "Please choose a CSV file." });
      return;
    }
    setUploading(true);
    setUploadMsg(null);
    try {
      const result: WarrantyUploadResult = await api.uploadWarrantyCsv(file);
      setUploadMsg({
        ok: true,
        text:
          `Saved ${result.savedCount} record${result.savedCount !== 1 ? "s" : ""}.` +
          (result.rowErrors.length ? ` ${result.rowErrors.length} row(s) skipped: ${result.rowErrors.slice(0, 3).join("; ")}` : ""),
      });
      if (fileRef.current) fileRef.current.value = "";
      load();
    } catch (e: unknown) {
      setUploadMsg({ ok: false, text: e instanceof Error ? e.message : "Upload failed" });
    } finally {
      setUploading(false);
    }
  };

  const covered = dedupedRows.filter((r) => r.status === "COVERED").length;
  const expiringSoon = dedupedRows.filter((r) => r.status === "EXPIRING_SOON").length;
  const expired = dedupedRows.filter((r) => r.status === "EXPIRED").length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-ink">Warranty Records</h1>
        <p className="mt-1 text-xs text-muted">
          Warranty data loaded from CSV exports. The newest row per serial number is shown, and hardware health
          uses it by matching on serial number.
        </p>
      </div>

      {!loading && !error && (
        <div className="grid grid-cols-3 gap-4">
          <div className="panel p-4">
            <div className="text-xs text-muted">Total serials</div>
            <div className="mt-2 text-2xl font-bold text-ink">{dedupedRows.length}</div>
          </div>
          <div className="panel p-4">
            <div className="text-xs text-muted">Covered</div>
            <div className="mt-2 text-2xl font-bold text-good">{covered}</div>
          </div>
          <div className="panel p-4">
            <div className="text-xs text-muted">Expiring soon / Expired</div>
            <div className="mt-2 text-2xl font-bold text-warn">
              {expiringSoon} <span className="text-bad">/ {expired}</span>
            </div>
          </div>
        </div>
      )}

      <div className="panel space-y-3 p-5">
        <h2 className="text-sm font-semibold text-ink">Upload CSV</h2>
        <p className="text-xs text-muted">
          Columns: <code className="rounded bg-panel2 px-1 py-0.5">serial_number</code>,{" "}
          <code className="rounded bg-panel2 px-1 py-0.5">vendor</code>,{" "}
          <code className="rounded bg-panel2 px-1 py-0.5">expires_on</code> (yyyy-MM-dd or MM/dd/yyyy), optional{" "}
          <code className="rounded bg-panel2 px-1 py-0.5">product_name</code>. Old rows are kept; the newest upload
          wins. Only administrators can upload.
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <input
            ref={fileRef}
            type="file"
            accept=".csv,text/csv"
            disabled={!mayUpload}
            className="text-xs file:mr-3 file:cursor-pointer file:rounded file:border-0 file:bg-accent file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-base disabled:opacity-50"
          />
          <button
            onClick={handleUpload}
            disabled={uploading || !mayUpload}
            title={!mayUpload ? DENIED_HINT : undefined}
            className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-base transition hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {uploading ? "Uploading…" : "Upload"}
          </button>
        </div>
        {uploadMsg && (
          <div
            className={`rounded-lg border px-3 py-2 text-xs font-medium ${
              uploadMsg.ok ? "border-good/30 bg-good/10 text-good" : "border-bad/30 bg-bad/10 text-bad"
            }`}
          >
            {uploadMsg.text}
          </div>
        )}
      </div>

      <div className="panel space-y-3 p-5">
        <div className="flex items-center gap-3">
          <input
            type="text"
            placeholder="Filter by serial, vendor or product…"
            className="flex-1 rounded-lg border border-border bg-base px-3 py-1.5 text-xs text-ink outline-none placeholder:text-muted focus:border-accent"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <span className="text-xs text-muted">{filtered.length} records</span>
        </div>

        {loading && <p className="text-xs text-muted">Loading…</p>}
        {error && <p className="text-xs text-bad">{error}</p>}
        {!loading && !error && filtered.length === 0 && (
          <p className="text-xs text-muted">
            {rows.length === 0
              ? "No warranty records yet. Upload a CSV to get started."
              : "No records match the current filter."}
          </p>
        )}

        {!loading && !error && filtered.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] border-collapse text-left text-xs">
              <thead>
                <tr className="border-b border-border text-[11px] font-semibold text-muted">
                  <th className="py-2 pr-4">Serial Number</th>
                  <th className="py-2 pr-4">Vendor</th>
                  <th className="py-2 pr-4">Product</th>
                  <th className="py-2 pr-4">Expires</th>
                  <th className="py-2 pr-4">Remaining</th>
                  <th className="py-2 pr-4">Status</th>
                  <th className="py-2">Uploaded by</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/40">
                {filtered.map((r) => (
                  <tr key={r.id} className="transition hover:bg-ink/[0.02]">
                    <td className="py-2.5 pr-4 font-mono">{r.serialNumber}</td>
                    <td className="py-2.5 pr-4 text-ink">{r.vendor}</td>
                    <td className="py-2.5 pr-4 text-muted">{r.productName ?? "—"}</td>
                    <td className="py-2.5 pr-4 font-mono">{r.expiresOn}</td>
                    <td className="py-2.5 pr-4 text-muted">{daysLabel(r.daysRemaining)}</td>
                    <td className="py-2.5 pr-4">
                      <StatusBadge value={r.status} />
                    </td>
                    <td className="py-2.5 text-muted">{r.uploadedBy}</td>
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