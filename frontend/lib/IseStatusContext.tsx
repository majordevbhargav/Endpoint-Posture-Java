"use client";

import { createContext, useCallback, useContext, useEffect, useState, ReactNode } from "react";
import { api, IseStatus } from "@/lib/api";
import { usePolling } from "@/lib/usePolling";

const IseStatusCtx = createContext<IseStatus | null>(null);

export function IseStatusProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<IseStatus | null>(null);

  const load = useCallback(async () => {
    try {
      setStatus(await api.iseStatus());
    } catch {
      // keep the last known status if the backend itself is briefly unreachable
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  usePolling(load, 10000);

  return <IseStatusCtx.Provider value={status}>{children}</IseStatusCtx.Provider>;
}

/** null until the first response arrives. */
export function useIseStatus(): IseStatus | null {
  return useContext(IseStatusCtx);
}

/** True only if the endpoint is flagged connected AND ISE is not known to be down. */
export function useIsLive() {
  const s = useIseStatus();
  return (connected: boolean) => connected && s?.reachable !== false;
}