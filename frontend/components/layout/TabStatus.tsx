"use client";

import { useEffect } from "react";
import { useIseStatus } from "@/lib/IseStatusContext";

function icon(color: string) {
  const svg =
    `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>` +
    `<rect width='32' height='32' rx='8' fill='#0f1110'/>` +
    `<path d='M16 5l9 3.5v7c0 5.5-3.8 9.5-9 11.5-5.2-2-9-6-9-11.5v-7z' fill='none' stroke='#2dd4bf' stroke-width='2.2' stroke-linejoin='round'/>` +
    `<circle cx='24' cy='24' r='5.5' fill='${color}' stroke='#0f1110' stroke-width='2'/>` +
    `</svg>`;
  return "data:image/svg+xml," + encodeURIComponent(svg);
}

export function TabStatus() {
  const ise = useIseStatus();
  const down = ise?.reachable === false;

  useEffect(() => {
    document.title = down ? "⚠ ISE unreachable · PostureEngine" : "PostureEngine";

    let link = document.querySelector<HTMLLinkElement>("link[rel='icon']");
    if (!link) {
      link = document.createElement("link");
      link.rel = "icon";
      document.head.appendChild(link);
    }
    link.type = "image/svg+xml";
    link.href = icon(ise === null ? "#949c97" : down ? "#f59e0b" : "#34d399");
  }, [down, ise]);

  return null;
}