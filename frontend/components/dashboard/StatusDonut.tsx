"use client";

export function StatusDonut({
  healthy,
  atRisk,
  critical,
  size = 140,
}: {
  healthy: number;
  atRisk: number;
  critical: number;
  size?: number;
}) {
  const total = healthy + atRisk + critical;
  const pct = (n: number) => (total === 0 ? 0 : (n / total) * 360);

  const healthyDeg = pct(healthy);
  const atRiskDeg = pct(atRisk);
  const criticalDeg = pct(critical);

  const gradient =
    total === 0
      ? "conic-gradient(rgb(var(--color-border)) 0deg 360deg)"
      : `conic-gradient(rgb(var(--color-good)) 0deg ${healthyDeg}deg, rgb(var(--color-warn)) ${healthyDeg}deg ${
          healthyDeg + atRiskDeg
        }deg, rgb(var(--color-bad)) ${healthyDeg + atRiskDeg}deg ${
          healthyDeg + atRiskDeg + criticalDeg
        }deg)`;

  return (
    <div className="flex items-center gap-6">
      <div
        className="relative flex-shrink-0 rounded-full shadow-inner"
        style={{ width: size, height: size, background: gradient }}
      >
        <div
          className="absolute flex flex-col items-center justify-center rounded-full bg-panel shadow-sm"
          style={{ inset: size * 0.18 }}
        >
          <span className="text-2xl font-bold tracking-tight text-ink">{total}</span>
          <span className="text-[11px] font-medium text-muted">Devices</span>
        </div>
      </div>
      <div className="flex flex-col gap-2.5 text-xs font-medium">
        <LegendRow color="rgb(var(--color-good))" label="Compliant" value={healthy} total={total} />
        <LegendRow color="rgb(var(--color-warn))" label="At Risk" value={atRisk} total={total} />
        <LegendRow color="rgb(var(--color-bad))" label="Critical" value={critical} total={total} />
      </div>
    </div>
  );
}

function LegendRow({
  color,
  label,
  value,
  total,
}: {
  color: string;
  label: string;
  value: number;
  total: number;
}) {
  const pct = total === 0 ? 0 : Math.round((value / total) * 100);
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="flex items-center gap-2">
        <span className="h-2.5 w-2.5 rounded-full" style={{ background: color }} />
        <span className="text-ink">{label}</span>
      </div>
      <span className="font-mono text-muted">
        {value} <span className="text-[10px] text-muted/70">({pct}%)</span>
      </span>
    </div>
  );
}