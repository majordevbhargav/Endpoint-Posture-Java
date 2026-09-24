"use client";

import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Download } from "lucide-react";

export const DEFAULT_PAGE_SIZES = [10, 25, 50, 100];

export function ExportCsvButton({
  onClick,
  disabled,
  label = "Export CSV",
}: {
  onClick: () => void;
  disabled?: boolean;
  label?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:border-accent/40 disabled:cursor-not-allowed disabled:opacity-50"
    >
      <Download size={13} className="text-muted" />
      <span>{label}</span>
    </button>
  );
}

export function PaginationBar({
  page,
  pageCount,
  pageSize,
  total,
  from,
  to,
  setPage,
  setPageSize,
  pageSizeOptions = DEFAULT_PAGE_SIZES,
}: {
  page: number;
  pageCount: number;
  pageSize: number;
  total: number;
  from: number;
  to: number;
  setPage: (p: number) => void;
  setPageSize: (n: number) => void;
  pageSizeOptions?: number[];
}) {
  const navBtn =
    "rounded-lg border border-border bg-panel p-1.5 text-muted transition hover:border-accent/40 hover:text-ink disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-border disabled:hover:text-muted";

  return (
    <div className="flex flex-col items-center justify-between gap-3 border-t border-border/60 px-4 py-3 text-xs text-muted sm:flex-row">
      <div>
        {total === 0 ? (
          "No results"
        ) : (
          <>
            Showing <span className="font-semibold text-ink">{from}</span>–
            <span className="font-semibold text-ink">{to}</span> of{" "}
            <span className="font-semibold text-ink">{total}</span>
          </>
        )}
      </div>

      <div className="flex items-center gap-3">
        <label className="flex items-center gap-1.5">
          <span>Rows per page</span>
          <select
            value={pageSize}
            onChange={(e) => setPageSize(Number(e.target.value))}
            className="rounded-lg border border-border bg-panel px-2 py-1 text-xs text-ink outline-none focus:border-accent"
          >
            {pageSizeOptions.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>

        <div className="flex items-center gap-1">
          <button className={navBtn} onClick={() => setPage(1)} disabled={page <= 1} title="First page" aria-label="First page">
            <ChevronsLeft size={13} />
          </button>
          <button className={navBtn} onClick={() => setPage(page - 1)} disabled={page <= 1} title="Previous page" aria-label="Previous page">
            <ChevronLeft size={13} />
          </button>
          <span className="min-w-[72px] text-center">
            Page <span className="font-semibold text-ink">{page}</span> / {pageCount}
          </span>
          <button className={navBtn} onClick={() => setPage(page + 1)} disabled={page >= pageCount} title="Next page" aria-label="Next page">
            <ChevronRight size={13} />
          </button>
          <button className={navBtn} onClick={() => setPage(pageCount)} disabled={page >= pageCount} title="Last page" aria-label="Last page">
            <ChevronsRight size={13} />
          </button>
        </div>
      </div>
    </div>
  );
}