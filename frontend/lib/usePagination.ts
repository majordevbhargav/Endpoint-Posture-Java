import { useCallback, useEffect, useMemo, useState } from "react";

/**
 * Client-side pagination over an already-filtered list.
 *
 * The current page is clamped whenever the list shrinks (a filter narrows the
 * results, or a poll returns fewer rows), so you never land on an empty page 7
 * of 3. Changing the page size returns to page 1.
 */
export function usePagination<T>(items: T[], initialPageSize = 25) {
  const [page, setPageRaw] = useState(1);
  const [pageSize, setPageSizeRaw] = useState(initialPageSize);

  const total = items.length;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(page, pageCount);

  useEffect(() => {
    if (page > pageCount) setPageRaw(pageCount);
  }, [page, pageCount]);

  const pageItems = useMemo(
    () => items.slice((safePage - 1) * pageSize, safePage * pageSize),
    [items, safePage, pageSize]
  );

  const setPage = useCallback((p: number) => setPageRaw(Math.max(1, p)), []);
  const setPageSize = useCallback((n: number) => {
    setPageSizeRaw(n);
    setPageRaw(1);
  }, []);
  const resetPage = useCallback(() => setPageRaw(1), []);

  return {
    page: safePage,
    pageSize,
    pageCount,
    pageItems,
    total,
    from: total === 0 ? 0 : (safePage - 1) * pageSize + 1,
    to: Math.min(total, safePage * pageSize),
    setPage,
    setPageSize,
    resetPage,
  };
}

export type PaginationState = ReturnType<typeof usePagination>;