"use client";

import { ShieldAlert } from "lucide-react";
import { SecurityIndicatorResponse } from "@/lib/api";
import { StatusBadge } from "@/components/ui/StatusBadge";

export function SecurityIndicatorsTab({
  latest,
  history,
}: {
  latest: SecurityIndicatorResponse | null;
  history: SecurityIndicatorResponse[];
}) {
  if (!latest) {
    return (
      <div className="panel p-8 text-center text-xs text-muted">
        No security scan has run for this device yet. Click &ldquo;Run Security Scan&rdquo; above. It samples the
        device&apos;s network connections for about a minute over WinRM.
      </div>
    );
  }

  if (latest.status !== "OK") {
    return (
      <div className="panel p-5">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-ink">
            {latest.status === "FAILED" ? "Security scan failed" : "Could not sample the endpoint"}
          </span>
          <StatusBadge value={latest.status} />
        </div>
        <div className="mt-1 text-xs text-muted">Attempted: {new Date(latest.collectedAt).toLocaleString()}</div>
        <div className="mt-3 break-words rounded-lg bg-base px-3 py-2 font-mono text-xs text-warn">
          {latest.errorMessage ?? "No detail recorded"}
        </div>
        <p className="mt-3 text-xs text-muted">
          No measurement is not a clean result. Enable WinRM on the device and, for a workgroup machine reached by IP,
          add it to this host&apos;s WinRM TrustedHosts.
        </p>
      </div>
    );
  }

  const s = latest.summary ?? {};
  const tiles: [string, unknown][] = [
    ["Samples", s.sampleCount],
    ["Internal hosts", s.internalDestinations],
    ["External hosts", s.externalDestinations],
    ["Admin-port targets", s.adminPortTargets],
  ];
  const findings = latest.findings ?? [];

  return (
    <div className="space-y-6">
      <div className="rounded-xl border border-border/80 bg-panel2/50 px-4 py-2.5 text-[11px] text-muted">
        These are <span className="font-semibold text-ink">possible</span> indicators from a short sampling window, for
        a person to review. Nothing here restricts a device or contacts Cisco ISE.
      </div>

      <div className="panel p-5">
        <div className="flex items-center justify-between">
          <div>
            <div className="flex items-center gap-2">
              <ShieldAlert size={15} className="text-accent" />
              <span className="text-sm font-semibold text-ink">Security indicators</span>
              {latest.riskLevel && <StatusBadge value={latest.riskLevel} />}
            </div>
            <div className="mt-1 text-xs text-muted">Sampled: {new Date(latest.collectedAt).toLocaleString()}</div>
          </div>
        </div>
        <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
          {tiles.map(([label, v]) => (
            <div key={label} className="rounded-xl border border-border/80 bg-base/50 p-3.5">
              <div className="text-xs text-muted">{label}</div>
              <div className="mt-1.5 font-mono text-2xl font-bold text-ink">{v == null ? "—" : String(v)}</div>
            </div>
          ))}
        </div>
        {s.beaconingAnalyzed === false && (
          <p className="mt-3 text-[11px] text-muted">Too few samples for beaconing analysis in this run.</p>
        )}
      </div>

      <div className="panel p-5">
        <div className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted">Findings</div>
        {findings.length === 0 ? (
          <div className="text-xs text-good">No indicators in this window.</div>
        ) : (
          <div className="space-y-2">
            {findings.map((f, i) => (
              <div key={i} className="rounded-lg border border-border/70 bg-base/40 p-3 text-xs">
                <div className="flex items-center gap-2">
                  <StatusBadge value={f.severity} />
                  <span className="font-semibold text-ink">{f.title}</span>
                </div>
                <div className="mt-1.5 text-muted">{f.detail}</div>
                <div className="mt-2 break-words rounded bg-base px-2 py-1.5 font-mono text-[10px] text-muted">
                  {JSON.stringify(f.evidence)}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {history.length > 1 && (
        <div className="panel p-5">
          <div className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted">
            Run history ({history.length})
          </div>
          <div className="divide-y divide-border/40 text-xs">
            {history.slice(0, 15).map((h) => (
              <div key={h.id} className="flex items-center justify-between py-2.5 first:pt-0 last:pb-0">
                <div className="flex items-center gap-2.5">
                  <StatusBadge value={h.status === "OK" && h.riskLevel ? h.riskLevel : h.status} />
                  <span className="text-muted">{new Date(h.collectedAt).toLocaleString()}</span>
                </div>
                <span className="font-mono text-[11px] text-muted">{h.findings?.length ?? 0} finding(s)</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}