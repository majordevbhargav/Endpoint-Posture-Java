import { useCallback, useEffect, useRef, useState } from "react";
import { usePolling } from "@/lib/usePolling";

/**
 * Module-level cache. It lives as long as the browser tab does, so it survives
 * navigating between pages (each page unmounts, this Map does not).
 */
const store = new Map<string, { data: unknown; at: number }>();

/** Call on sign-out / sign-in so one user's data is never shown to the next. */
export function clearFetchCache() {
  store.clear();
}

/**
 * Stale-while-revalidate data hook.
 *
 * - Coming back to a page shows the cached data instantly (no loading flash).
 * - It only refetches if the cache is older than `ttlMs`, so hopping between
 *   pages no longer hammers the backend.
 * - It keeps polling every `pollMs` while the tab is visible; each poll also
 *   respects `ttlMs`.
 * - A failed refresh keeps the last good data on screen.
 */
export function useCachedFetch<T>(
  key: string,
  fetcher: () => Promise<T>,
  { ttlMs = 15000, pollMs = 20000 }: { ttlMs?: number; pollMs?: number } = {}
) {
  const [data, setData] = useState<T | null>(() => (store.get(key)?.data as T | undefined) ?? null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(false);

  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const inflight = useRef(false);

  const load = useCallback(
    async (force = false) => {
      const cached = store.get(key);
      if (!force && cached && Date.now() - cached.at < ttlMs) return;
      if (inflight.current) return;

      inflight.current = true;
      setRefreshing(true);
      try {
        const fresh = await fetcherRef.current();
        store.set(key, { data: fresh, at: Date.now() });
        setData(fresh);
        setError(false);
      } catch {
        setError(true); // keep whatever data we already have
      } finally {
        inflight.current = false;
        setRefreshing(false);
      }
    },
    [key, ttlMs]
  );

  // First load on mount: instant if cached and fresh, otherwise one fetch.
  useEffect(() => {
    load();
  }, [load]);

  usePolling(() => load(), pollMs);

  return { data, refreshing, error, reload: () => load(true) };
}