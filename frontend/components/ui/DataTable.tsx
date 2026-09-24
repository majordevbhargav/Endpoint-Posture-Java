"use client";

import { ReactNode } from "react";
import { CsvValue, datedFilename, downloadCsv } from "@/lib/csv";
import { usePagination } from "@/lib/usePagination";
import { ExportCsvButton, PaginationBar } from "@/components/ui/PaginationBar";

export interface Column<T> {
  /** Stable key; also the fallback property read from the row for display/CSV. */
  key: string;
  header: string;
  /** Cell renderer. Defaults to the row's `key` property (or an em dash). */
  render?: (row: T) => ReactNode;
  /** CSV value. Defaults to the row's `key` property. Set for columns whose `render` isn't plain text. */
  csv?: (row: T) => CsvValue;
  /** Set false for action/link columns that shouldn't appear in the CSV. Default true. */
  exportable?: boolean;
  className?: string;
  headerClassName?: string;
}

interface DataTableProps<T> {
  /** `null` means "still loading". */
  rows: T[] | null;
  columns: Column<T>[];
  rowKey: (row: T) => string;
  /** Base filename for CSV export; today's date is appended. */
  csvFilename: string;
  emptyMessage?: string;
  loadingMessage?: string;
  defaultPageSize?: number;
  pageSizeOptions?: number[];
  minWidth?: number;
  /** Extra content on the left of the toolbar (e.g. a title). */
  toolbarLeft?: ReactNode;
}

function readProp(row: unknown, key: string): unknown {
  return (row as Record<string, unknown>)[key];
}

/**
 * Paginated table with CSV export.
 *
 * Pass the rows AFTER your own search/filter logic: pagination slices what you
 * give it, and "Export CSV" exports every row you gave it (all pages, not just
 * the visible one), so the file always matches the active filters.
 */
export function DataTable<T>({
  rows,
  columns,
  rowKey,
  csvFilename,
  emptyMessage = "No records found.",
  loadingMessage = "Loading…",
  defaultPageSize = 25,
  pageSizeOptions,
  minWidth = 720,
  toolbarLeft,
}: DataTableProps<T>) {
  const list = rows ?? [];
  const pager = usePagination(list, defaultPageSize);

  function exportCsv() {
    const exportCols = columns.filter((c) => c.exportable !== false);
    downloadCsv(
      datedFilename(csvFilename),
      exportCols.map((c) => c.header),
      list.map((row) =>
        exportCols.map((c) => {
          if (c.csv) return c.csv(row);
          const v = readProp(row, c.key);
          return v === null || v === undefined ? "" : (v as CsvValue);
        })
      )
    );
  }

  return (
    <div className="panel overflow-hidden">
      <div className="flex items-center justify-between gap-3 border-b border-border/60 px-4 py-2.5">
        <div className="text-xs text-muted">{toolbarLeft}</div>
        <ExportCsvButton onClick={exportCsv} disabled={rows === null || list.length === 0} />
      </div>

      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-left text-xs" style={{ minWidth }}>
          <thead>
            <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
              {columns.map((c) => (
                <th key={c.key} className={`px-4 py-3 ${c.headerClassName ?? ""}`}>
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-border/40">
            {rows === null && (
              <tr>
                <td colSpan={columns.length} className="py-12 text-center text-muted">
                  {loadingMessage}
                </td>
              </tr>
            )}
            {rows !== null && list.length === 0 && (
              <tr>
                <td colSpan={columns.length} className="py-12 text-center text-muted">
                  {emptyMessage}
                </td>
              </tr>
            )}
            {pager.pageItems.map((row) => (
              <tr key={rowKey(row)} className="transition hover:bg-ink/[0.02]">
                {columns.map((c) => (
                  <td key={c.key} className={`px-4 py-3 ${c.className ?? ""}`}>
                    {c.render ? c.render(row) : String(readProp(row, c.key) ?? "—")}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {rows !== null && (
        <PaginationBar
          page={pager.page}
          pageCount={pager.pageCount}
          pageSize={pager.pageSize}
          total={pager.total}
          from={pager.from}
          to={pager.to}
          setPage={pager.setPage}
          setPageSize={pager.setPageSize}
          pageSizeOptions={pageSizeOptions}
        />
      )}
    </div>
  );
}