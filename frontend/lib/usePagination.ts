import { useCallback, useEffect, useMemo, useState } from "react";

// Remembers the chosen page size per table for as long as the tab lives.
// The Map survives client-side navigation (no flash); sessionStorage survives a refresh.
const sizeMemory = new Map<string, number>();
const STORAGE_PREFIX = "pageSize:";

/**
 * Pure pagination math, kept separate from the hook so it can be unit tested.
 * The requested page is clamped into 1..pageCount, so a list that shrinks
 * never leaves you on an empty page.
 */
export function computePagination<T>(items: T[], page: number, pageSize: number) {
  const total = items.length;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(1, page), pageCount);
  return {
    total,
    pageCount,
    page: safePage,
    pageItems: items.slice((safePage - 1) * pageSize, safePage * pageSize),
    from: total === 0 ? 0 : (safePage - 1) * pageSize + 1,
    to: Math.min(total, safePage * pageSize),
  };
}

/**
 * Client-side pagination over an already-filtered list.
 *
 * The current page is clamped whenever the list shrinks (a filter narrows the
 * results, or a poll returns fewer rows), so you never land on an empty page 7
 * of 3. Changing the page size returns to page 1.
 *
 * Pass a `storageKey` (unique per table) and the chosen page size is
 * remembered when you leave the page and come back.
 */
export function usePagination<T>(items: T[], initialPageSize = 25, storageKey?: string) {
  const [page, setPageRaw] = useState(1);
  const [pageSize, setPageSizeRaw] = useState<number>(
    () => (storageKey ? sizeMemory.get(storageKey) : undefined) ?? initialPageSize
  );

  // After a full page refresh the Map is empty, so restore from sessionStorage once.
  useEffect(() => {
    if (!storageKey || sizeMemory.has(storageKey)) return;
    try {
      const saved = Number(sessionStorage.getItem(STORAGE_PREFIX + storageKey));
      if (saved > 0) {
        sizeMemory.set(storageKey, saved);
        setPageSizeRaw(saved);
      }
    } catch {
      // storage unavailable: fall back to the default
    }
  }, [storageKey]);

  const view = useMemo(() => computePagination(items, page, pageSize), [items, page, pageSize]);

  useEffect(() => {
    if (page > view.pageCount) setPageRaw(view.pageCount);
  }, [page, view.pageCount]);

  const setPage = useCallback((p: number) => setPageRaw(Math.max(1, p)), []);

  const setPageSize = useCallback(
    (n: number) => {
      setPageSizeRaw(n);
      setPageRaw(1);
      if (storageKey) {
        sizeMemory.set(storageKey, n);
        try {
          sessionStorage.setItem(STORAGE_PREFIX + storageKey, String(n));
        } catch {
          // ignore
        }
      }
    },
    [storageKey]
  );

  const resetPage = useCallback(() => setPageRaw(1), []);

  return {
    page: view.page,
    pageSize,
    pageCount: view.pageCount,
    pageItems: view.pageItems,
    total: view.total,
    from: view.from,
    to: view.to,
    setPage,
    setPageSize,
    resetPage,
  };
}

export type PaginationState = ReturnType<typeof usePagination>;