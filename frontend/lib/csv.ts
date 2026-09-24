export type CsvValue = string | number | boolean | null | undefined;

/**
 * Escapes one CSV cell.
 *
 * - Quotes cells containing commas, quotes or newlines (RFC 4180).
 * - Neutralizes spreadsheet formula injection: audit/job "detail" text comes
 *   from remote agents and ISE, so a value starting with = + - @ would be
 *   executed as a formula when the CSV is opened in Excel. Prefixing a single
 *   quote makes Excel treat it as plain text. Real numbers are left untouched.
 */
function escapeCell(value: CsvValue): string {
  if (value === null || value === undefined) return "";

  let text: string;
  if (typeof value === "number" || typeof value === "boolean") {
    text = String(value);
  } else {
    text = value;
    if (/^[=+\-@\t\r]/.test(text)) text = "'" + text;
  }

  if (/[",\r\n]/.test(text)) {
    return '"' + text.replace(/"/g, '""') + '"';
  }
  return text;
}

export function toCsv(headers: string[], rows: CsvValue[][]): string {
  const lines = [headers, ...rows].map((r) => r.map(escapeCell).join(","));
  return lines.join("\r\n");
}

/** Builds a CSV and triggers a browser download. UTF-8 BOM keeps Excel from mangling non-ASCII. */
export function downloadCsv(filename: string, headers: string[], rows: CsvValue[][]) {
  const csv = "\uFEFF" + toCsv(headers, rows);
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);

  const a = document.createElement("a");
  a.href = url;
  a.download = filename.endsWith(".csv") ? filename : `${filename}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/** e.g. "jobs-2026-09-24" - keeps exported files from overwriting each other. */
export function datedFilename(base: string): string {
  return `${base}-${new Date().toISOString().slice(0, 10)}`;
}