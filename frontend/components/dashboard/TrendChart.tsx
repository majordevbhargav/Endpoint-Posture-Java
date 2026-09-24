"use client";

export function TrendChart({
  points,
  height = 120,
}: {
  points: number[]; // 0-100 values, oldest first
  height?: number;
}) {
  if (!points || points.length < 2) {
    return (
      <div
        className="flex items-center justify-center rounded-lg border border-dashed border-border text-center text-xs text-muted"
        style={{ height }}
      >
        Historical trend active after multiple posture assessment cycles.
      </div>
    );
  }

  const width = 100;
  const max = 100;
  const min = 0;
  const stepX = width / (points.length - 1);

  const coords = points.map((p, i) => {
    const x = i * stepX;
    const y = height - ((p - min) / (max - min)) * height;
    return [x, y];
  });

  const linePath = coords
    .map(([x, y], i) => `${i === 0 ? "M" : "L"}${x},${y}`)
    .join(" ");
  const areaPath = `${linePath} L${width},${height} L0,${height} Z`;

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      className="w-full overflow-visible"
      style={{ height }}
    >
      <defs>
        <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="rgb(var(--color-accent))" stopOpacity="0.3" />
          <stop offset="100%" stopColor="rgb(var(--color-accent))" stopOpacity="0.0" />
        </linearGradient>
      </defs>
      <path d={areaPath} fill="url(#trendFill)" />
      <path
        d={linePath}
        fill="none"
        stroke="rgb(var(--color-accent))"
        strokeWidth="2"
        vectorEffect="non-scaling-stroke"
      />
      {coords.map(([x, y], i) => (
        <circle
          key={i}
          cx={x}
          cy={y}
          r="2.5"
          fill="rgb(var(--color-accent))"
          className="transition-transform hover:scale-150"
          vectorEffect="non-scaling-stroke"
        />
      ))}
    </svg>
  );
}