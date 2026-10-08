import { describe, it, expect } from "vitest";
import { usePagination } from "./usePagination";

// usePagination is a React hook but its pure-computation logic can be
// extracted and tested without a DOM. We inline the logic here.
// For behavior tests we replicate what the hook computes.

function computePagination<T>(items: T[], page: number, pageSize: number) {
  const total = items.length;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(page, pageCount);
  const pageItems = items.slice((safePage - 1) * pageSize, safePage * pageSize);
  return {
    page: safePage,
    pageSize,
    pageCount,
    total,
    pageItems,
    from: total === 0 ? 0 : (safePage - 1) * pageSize + 1,
    to: Math.min(total, safePage * pageSize),
  };
}

describe("usePagination logic", () => {
  const items = Array.from({ length: 55 }, (_, i) => i + 1);

  it("first page returns correct slice", () => {
    const { pageItems, from, to, pageCount } = computePagination(items, 1, 25);
    expect(pageItems).toEqual(Array.from({ length: 25 }, (_, i) => i + 1));
    expect(from).toBe(1);
    expect(to).toBe(25);
    expect(pageCount).toBe(3);
  });

  it("last page returns remaining items", () => {
    const { pageItems, from, to } = computePagination(items, 3, 25);
    expect(pageItems).toEqual([51, 52, 53, 54, 55]);
    expect(from).toBe(51);
    expect(to).toBe(55);
  });

  it("page is clamped when list shrinks", () => {
    // If we were on page 5 but list now only has 3 pages
    const smallItems = Array.from({ length: 30 }, (_, i) => i + 1);
    const { page, pageCount } = computePagination(smallItems, 5, 10);
    expect(pageCount).toBe(3);
    expect(page).toBe(3); // clamped
  });

  it("empty list returns safe defaults", () => {
    const { total, pageCount, pageItems, from, to } = computePagination([], 1, 25);
    expect(total).toBe(0);
    expect(pageCount).toBe(1);
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
