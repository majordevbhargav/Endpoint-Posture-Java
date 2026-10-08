import { describe, it, expect } from "vitest";
import { computePagination } from "./usePagination";

describe("computePagination", () => {
  const items = Array.from({ length: 55 }, (_, i) => i + 1);

  it("first page returns the correct slice", () => {
    const { pageItems, from, to, pageCount } = computePagination(items, 1, 25);
    expect(pageItems).toEqual(Array.from({ length: 25 }, (_, i) => i + 1));
    expect(from).toBe(1);
    expect(to).toBe(25);
    expect(pageCount).toBe(3);
  });

  it("last page returns the remaining items", () => {
    const { pageItems, from, to } = computePagination(items, 3, 25);
    expect(pageItems).toEqual([51, 52, 53, 54, 55]);
    expect(from).toBe(51);
    expect(to).toBe(55);
  });

  it("clamps a page that is past the end after the list shrinks", () => {
    const small = Array.from({ length: 30 }, (_, i) => i + 1);
    const { page, pageCount } = computePagination(small, 5, 10);
    expect(pageCount).toBe(3);
    expect(page).toBe(3);
  });

  it("clamps page 0 or a negative page up to 1", () => {
    expect(computePagination(items, 0, 25).page).toBe(1);
    expect(computePagination(items, -4, 25).page).toBe(1);
  });

  it("empty list returns safe defaults", () => {
    const { total, pageCount, pageItems, from, to, page } = computePagination([], 1, 25);
    expect(total).toBe(0);
    expect(pageCount).toBe(1);
    expect(page).toBe(1);
    expect(pageItems).toHaveLength(0);
    expect(from).toBe(0);
    expect(to).toBe(0);
  });

  it("single item list works", () => {
    const { from, to, pageItems } = computePagination([42], 1, 25);
    expect(from).toBe(1);
    expect(to).toBe(1);
    expect(pageItems).toEqual([42]);
  });
});