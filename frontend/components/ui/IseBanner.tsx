"use client";

import { AlertTriangle } from "lucide-react";
import { useIseStatus } from "@/lib/IseStatusContext";

export function IseBanner() {
  const ise = useIseStatus();
  if (!ise || ise.reachable) return null;

  return (
    <div className="flex items-start gap-2 border-b border-warn/30 bg-warn/10 px-6 py-2 text-xs text-warn">
      <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" />
      <div>
        <span className="font-semibold">Cisco ISE is unreachable.</span> Session status is frozen at its last known
        state
        {ise.lastSuccessAt && <> (last update {new Date(ise.lastSuccessAt).toLocaleTimeString()})</>}. Stored
        posture, hardware and history data remain available.
      </div>
    </div>
  );
}