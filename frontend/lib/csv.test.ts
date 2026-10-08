import { describe, it, expect } from "vitest";
import { toCsv, datedFilename } from "./csv";

describe("csv utilities", () => {
  it("quotes cells containing commas, quotes, or newlines", () => {
    const headers = ["name", "notes"];
    const rows = [
      ["normal", "simple text"],
      ["with,comma", 'with "quotes"'],
      ["with\nnewline", "ok"],
    ];
    const csv = toCsv(headers, rows);
    expect(csv).toContain('"with,comma"');
    expect(csv).toContain('"with ""quotes"""');
    expect(csv).toContain('"with\nnewline"');
  });

  it("neutralizes spreadsheet formula injection with leading single quote", () => {
    const headers = ["type", "formula"];
    const rows = [
      ["equals", "=SUM(A1:B1)"],
      ["plus", "+12345"],
      ["minus", "-cmd|' /C calc'!A0"],
      ["at", "@SUM(1+1)"],
      ["tab", "\tsecret"],
    ];
    const csv = toCsv(headers, rows);
    expect(csv).toContain("'=SUM(A1:B1)");
    expect(csv).toContain("'+12345");
    expect(csv).toContain("'-cmd|' /C calc'!A0");
    expect(csv).toContain("'@SUM(1+1)");
    expect(csv).toContain("'\tsecret");
  });

  it("leaves real numbers and booleans unquoted and untouched", () => {
    const headers = ["id", "count", "active", "missing"];
    const rows = [[1, 42, true, null]];
    const csv = toCsv(headers, rows);
    expect(csv).toContain("1,42,true,");
  });

  it("generates dated filenames with YYYY-MM-DD format", () => {
    const name = datedFilename("compliance-export");
    expect(name).toMatch(/^compliance-export-\d{4}-\d{2}-\d{2}$/);
  });
});
