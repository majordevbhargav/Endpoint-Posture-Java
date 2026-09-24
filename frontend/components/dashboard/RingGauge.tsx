"use client";

function colorFor(value: number) {
  if (value >= 80) return "rgb(var(--color-good))";
  if (value >= 60) return "rgb(var(--color-warn))";
  return "rgb(var(--color-bad))";
}

function labelFor(value: number) {
  if (value >= 80) return "Optimal Posture";
  if (value >= 60) return "Needs Review";
  return "Action Required";
}

export function RingGauge({
  value,
  size = 176,
  stroke = 14,
}: {
  value: number; // 0-100
  size?: number;
  stroke?: number;
}) {
  const v = Math.max(0, Math.min(100, value));
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference * (1 - v / 100);
  const color = colorFor(v);

  return (
    <div className="flex flex-col items-center">
      <div className="relative flex items-center justify-center" style={{ width: size, height: size }}>
        <svg width={size} height={size} className="-rotate-90">
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            stroke="rgb(var(--color-border) / 0.7)"
            strokeWidth={stroke}
            fill="none"
          />
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            stroke={color}
            strokeWidth={stroke}
            strokeLinecap="round"
            fill="none"
            strokeDasharray={circumference}
            strokeDashoffset={offset}
            style={{ transition: "stroke-dashoffset 0.8s cubic-bezier(0.4, 0, 0.2, 1)" }}
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className="text-4xl font-extrabold tracking-tight text-ink">
            {Math.round(v)}
            <span className="text-base font-semibold text-muted">%</span>
          </span>
          <span className="mt-0.5 text-xs font-semibold" style={{ color }}>
            {labelFor(v)}
          </span>
        </div>
      </div>
      <div className="mt-2 text-xs text-muted font-medium">Compliance Index</div>
    </div>
  );
}