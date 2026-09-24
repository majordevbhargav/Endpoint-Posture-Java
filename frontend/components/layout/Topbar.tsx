"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Search, ChevronDown, LogOut, Monitor, ExternalLink, Shield } from "lucide-react";
import { api, clearToken, EndpointResponse } from "@/lib/api";
import { clearCurrentUser, getCurrentUser, initials, CurrentUser } from "@/lib/auth";
import { ThemeToggle } from "./ThemeToggle";
import { ConnectionDot } from "../ui/ConnectionDot";

export function Topbar() {
  const router = useRouter();
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState("");
  const [searchResults, setSearchResults] = useState<EndpointResponse[]>([]);
  const [allEndpoints, setAllEndpoints] = useState<EndpointResponse[]>([]);
  const [searchFocused, setSearchFocused] = useState(false);

  const menuRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setUser(getCurrentUser());
    api.listEndpoints().then(setAllEndpoints).catch(() => {});
  }, []);

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
      if (searchRef.current && !searchRef.current.contains(e.target as Node)) {
        setSearchFocused(false);
      }
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, []);

  useEffect(() => {
    if (!searchTerm.trim()) {
      setSearchResults([]);
      return;
    }
    const q = searchTerm.toLowerCase();
    const hits = allEndpoints.filter(
      (ep) =>
        ep.macAddress.toLowerCase().includes(q) ||
        (ep.ipAddress && ep.ipAddress.toLowerCase().includes(q)) ||
        (ep.hostname && ep.hostname.toLowerCase().includes(q)) ||
        (ep.osName && ep.osName.toLowerCase().includes(q))
    );
    setSearchResults(hits.slice(0, 5));
  }, [searchTerm, allEndpoints]);

  function signOut() {
    clearToken();
    clearCurrentUser();
    router.push("/login");
  }

  const displayName = user?.username ?? "Security Admin";
  const displayRole = user?.role ?? "Operator";

  return (
    <header className="flex h-14 items-center justify-between border-b border-border bg-base/80 px-6 backdrop-blur">
      {/* Search Input with Live Dropdown */}
      <div className="relative w-80 sm:w-96" ref={searchRef}>
        <Search
          size={15}
          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted"
        />
        <input
          type="text"
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          onFocus={() => setSearchFocused(true)}
          placeholder="Quick search endpoints by MAC, IP, host…"
          className="w-full rounded-lg border border-border bg-panel py-1.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none transition focus:border-accent"
        />

        {searchFocused && searchTerm.trim() && (
          <div className="absolute left-0 top-11 z-50 w-full overflow-hidden rounded-xl border border-border bg-panel shadow-lg">
            <div className="border-b border-border/60 px-3 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted">
              Matching Devices ({searchResults.length})
            </div>
            {searchResults.length === 0 ? (
              <div className="p-3 text-center text-xs text-muted">
                No matching endpoint found.
              </div>
            ) : (
              <div className="divide-y divide-border/40">
                {searchResults.map((ep) => (
                  <Link
                    key={ep.id}
                    href={`/endpoints/${ep.id}`}
                    onClick={() => {
                      setSearchFocused(false);
                      setSearchTerm("");
                    }}
                    className="flex items-center justify-between p-2.5 hover:bg-ink/[0.04]"
                  >
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <Monitor size={13} className="text-accent" />
                        <span className="truncate text-xs font-semibold text-ink">
                          {ep.hostname ?? ep.macAddress}
                        </span>
                      </div>
                      <div className="font-mono text-[10px] text-muted">
                        {ep.ipAddress ?? "No IP"} · {ep.macAddress}
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <ConnectionDot connected={ep.connected} />
                      <ExternalLink size={12} className="text-muted" />
                    </div>
                  </Link>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Right controls: Theme switcher & Operator Profile */}
      <div className="flex items-center gap-3">
        <ThemeToggle />

        {/* User Profile */}
        <div className="relative" ref={menuRef}>
          <button
            onClick={() => setMenuOpen((v) => !v)}
            className="flex items-center gap-2.5 rounded-lg border border-border/80 bg-panel px-2 py-1 transition hover:border-accent/40"
          >
            <div className="flex h-7 w-7 items-center justify-center rounded-md bg-accent/15 text-xs font-bold text-accent">
              {initials(displayName)}
            </div>
            <div className="hidden text-left leading-tight sm:block">
              <div className="text-xs font-semibold text-ink">{displayName}</div>
              <div className="text-[10px] text-muted capitalize">{displayRole}</div>
            </div>
            <ChevronDown size={13} className="text-muted" />
          </button>

          {menuOpen && (
            <div className="absolute right-0 top-11 z-50 w-48 overflow-hidden rounded-xl border border-border bg-panel shadow-xl">
              <div className="border-b border-border/60 p-3">
                <div className="text-xs font-semibold text-ink">{displayName}</div>
                <div className="text-[10px] text-muted">Continuous Compliance Operator</div>
              </div>
              <div className="p-1">
                <button
                  onClick={signOut}
                  className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs font-medium text-bad hover:bg-bad/10 transition"
                >
                  <LogOut size={14} />
                  Sign out session
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}