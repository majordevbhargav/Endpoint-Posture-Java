"use client";

import { useEffect, useState } from "react";
import { Sun, Moon } from "lucide-react";

export function ThemeToggle() {
  const [light, setLight] = useState(false);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    setLight(document.documentElement.classList.contains("light"));
  }, []);

  function toggle() {
    const next = !light;
    setLight(next);
    document.documentElement.classList.toggle("light", next);
    localStorage.setItem("theme", next ? "light" : "dark");
  }

  if (!mounted) {
    return (
      <div className="h-9 w-9 rounded-lg border border-border bg-panel" />
    );
  }

  return (
    <button
      onClick={toggle}
      aria-label="Toggle theme"
      title={light ? "Switch to Dark Mode" : "Switch to Light Mode"}
      className="group relative flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg border border-border bg-panel text-muted transition hover:border-accent/40 hover:text-ink focus:outline-none"
    >
      {light ? (
        <Moon size={16} className="transition-transform group-hover:-rotate-12 text-accent" />
      ) : (
        <Sun size={16} className="transition-transform group-hover:rotate-45 text-amber-400" />
      )}
    </button>
  );
}