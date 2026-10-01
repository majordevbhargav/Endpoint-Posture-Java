"use client";

import { useEffect, useRef, useState } from "react";
import { TrendPoint } from "@/lib/api";

export interface ChartSeries {
  name: string;
  color: string;
  values: (number | null)[];
}

export interface TrendChartProps {
  data?: TrendPoint[];
  labels?: string[];
  series?: ChartSeries[];
  height?: number;
  emptyMessage?: string;
}

export function TrendChart({
  data,
  labels: propLabels,
  series: propSeries,
  height = 140,
  emptyMessage,
}: TrendChartProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [W, setW] = useState(0);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    setW(el.clientWidth);
    const ro = new ResizeObserver(([entry]) => setW(entry.contentRect.width));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Mode 1: data (TrendPoint[]) passed (legacy single-series compliance trend)
  if (data !== undefined) {
    const withValue = data.filter((d) => d.compliantPercent != null);
    const H = height, L = 34, R = 12, T = 8, B = 22;
    const plotW = Math.max(0, W - L - R);
    const plotH = H - T - B;
    const x = (i: number) => L + (data.length === 1 ? plotW / 2 : (i / (data.length - 1)) * plotW);
    const y = (v: number) => T + plotH - (v / 100) * plotH;

    let path = "";
    let pen = false;
    data.forEach((d, i) => {
      if (d.compliantPercent == null) { pen = false; return; }
      path += `${pen ? "L" : "M"}${x(i)},${y(d.compliantPercent)} `;
      pen = true;
    });

    return (
      <div ref={ref} className="w-full">
        {withValue.length === 0 ? (
          <div
            className="flex items-center justify-center rounded-lg border border-dashed border-border text-center text-xs text-muted"
            style={{ height }}
          >
            {emptyMessage || "No assessments in this period yet. The trend fills in as posture checks run."}
          </div>
        ) : W > 0 ? (
          <>
            <svg width={W} height={H} role="img" aria-label="Compliance trend" className="block">
              {[0, 50, 100].map((g) => (
                <g key={g}>
                  <line x1={L} x2={W - R} y1={y(g)} y2={y(g)} stroke="rgb(var(--color-border))" strokeDasharray="3 4" />
                  <text x={L - 6} y={y(g) + 3} textAnchor="end" fontSize="10" fill="rgb(var(--color-muted))">{g}%</text>
                </g>
              ))}

              <path d={path} fill="none" stroke="rgb(var(--color-accent))" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />

              {data.map((d, i) => (
                <g key={d.date}>
                  {d.compliantPercent != null && (
                    <circle cx={x(i)} cy={y(d.compliantPercent)} r="3.5" fill="rgb(var(--color-accent))" stroke="rgb(var(--color-panel))" strokeWidth="1.5">
                      <title>{`${d.date}: ${d.compliantPercent}% compliant (${d.assessed} device${d.assessed === 1 ? "" : "s"} assessed)`}</title>
                    </circle>
                  )}
                  <text x={x(i)} y={H - 6} textAnchor="middle" fontSize="10" fill="rgb(var(--color-muted))">
                    {d.date.slice(5)}
                  </text>
                </g>
              ))}
            </svg>
            {withValue.length < 2 && (
              <p className="mt-1 text-xs text-muted">Only {withValue.length} day of data so far, so no line can be drawn yet.</p>
            )}
          </>
        ) : null}
      </div>
    );
  }

  // Mode 2: Multi-series (e.g. Hardware trend)
  const labels = propLabels ?? [];
  const series = propSeries ?? [];
  const pointCount = labels.length;

  if (pointCount < 2) {
    return (
      <div ref={ref} className="w-full">
        <div
          className="flex items-center justify-center rounded-lg border border-dashed border-border p-4 text-center text-xs text-muted"
          style={{ height }}
        >
          {emptyMessage || "At least 2 data points are required to render trend charts."}
        </div>
      </div>
    );
  }

  const H = height, L = 34, R = 12, T = 12, B = 24;
  const plotW = Math.max(0, W - L - R);
  const plotH = Math.max(0, H - T - B);
  const x = (i: number) => L + (pointCount <= 1 ? plotW / 2 : (i / (pointCount - 1)) * plotW);
  const y = (v: number) => T + plotH - (Math.max(0, Math.min(100, v)) / 100) * plotH;

  return (
    <div ref={ref} className="w-full space-y-3">
      {/* Legend */}
      <div className="flex flex-wrap items-center gap-4 text-xs font-medium">
        {series.map((s) => (
          <div key={s.name} className="flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: s.color }} />
            <span className="text-ink">{s.name}</span>
          </div>
        ))}
      </div>

      {W > 0 && (
        <svg width={W} height={H} role="img" aria-label="Hardware trend" className="block overflow-visible">
          {[0, 50, 100].map((g) => (
            <g key={g}>
              <line x1={L} x2={W - R} y1={y(g)} y2={y(g)} stroke="rgb(var(--color-border))" strokeDasharray="3 4" />
              <text x={L - 6} y={y(g) + 3} textAnchor="end" fontSize="10" fill="rgb(var(--color-muted))">{g}</text>
            </g>
          ))}

          {/* Series lines */}
          {series.map((s) => {
            let path = "";
            let pen = false;
            s.values.forEach((val, i) => {
              if (val == null) {
                pen = false;
                return;
              }
              path += `${pen ? "L" : "M"}${x(i)},${y(val)} `;
              pen = true;
            });
            return (
              <path
                key={s.name}
                d={path}
                fill="none"
                stroke={s.color}
                strokeWidth={s.name === "Overall" ? "2.5" : "1.75"}
                strokeLinejoin="round"
                strokeLinecap="round"
                opacity={s.name === "Overall" ? 1 : 0.85}
              />
            );
          })}

          {/* Points */}
          {series.map((s) => (
            <g key={`points-${s.name}`}>
              {s.values.map((val, i) => {
                if (val == null) return null;
                return (
                  <circle
                    key={`${s.name}-${i}`}
                    cx={x(i)}
                    cy={y(val)}
                    r={s.name === "Overall" ? 3.5 : 2.5}
                    fill={s.color}
                    stroke="rgb(var(--color-panel))"
                    strokeWidth="1.5"
                  >
                    <title>{`${labels[i]} — ${s.name}: ${val}/100`}</title>
                  </circle>
                );
              })}
            </g>
          ))}

          {/* X-axis labels */}
          {labels.map((lbl, i) => (
            <text key={`label-${i}`} x={x(i)} y={H - 4} textAnchor="middle" fontSize="10" fill="rgb(var(--color-muted))">
              {lbl}
            </text>
          ))}
        </svg>
      )}
    </div>
  );
}