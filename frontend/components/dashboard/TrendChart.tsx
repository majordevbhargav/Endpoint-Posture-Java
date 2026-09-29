"use client";

import { useEffect, useRef, useState } from "react";
import { TrendPoint } from "@/lib/api";

export function TrendChart({ data, height = 140 }: { data: TrendPoint[]; height?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const [W, setW] = useState(0);

  // The wrapper below is always rendered, so the ref exists on first mount.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    setW(el.clientWidth);
    const ro = new ResizeObserver(([entry]) => setW(entry.contentRect.width));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

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
          No assessments in this period yet. The trend fills in as posture checks run.
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