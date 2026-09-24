"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ShieldCheck, Lock, User, ArrowRight } from "lucide-react";
import { api, setToken } from "@/lib/api";
import { setCurrentUser } from "@/lib/auth";
import { ThemeToggle } from "@/components/layout/ThemeToggle";

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!username.trim() || !password.trim()) {
      setError("Please enter both username and password.");
      return;
    }

    setError(null);
    setLoading(true);
    try {
      const res = await api.login(username, password);
      setToken(res.token);
      setCurrentUser({ username: res.username || username, role: res.role || "admin" });
      router.push("/overview");
    } catch (err) {
      const msg = err instanceof Error ? err.message : "";
      if (msg.includes("401") || msg.toLowerCase().includes("unauthorized") || msg.toLowerCase().includes("bad credentials")) {
        setError("Invalid username or password.");
      } else if (msg.includes("500") || msg.toLowerCase().includes("failed to fetch") || msg.toLowerCase().includes("network")) {
        setError("Unable to reach backend server (localhost:8090). Please ensure the backend is running.");
      } else {
        setError(msg || "Invalid username or password.");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-base px-4">
      {/* Top right theme toggle */}
      <div className="absolute right-6 top-6 z-20">
        <ThemeToggle />
      </div>

      <div className="w-full max-w-sm rounded-2xl border border-border bg-panel p-7 shadow-xl transition-all">
        {/* Header */}
        <div className="mb-6 flex flex-col items-center text-center">
          <div className="mb-3 flex h-11 w-11 items-center justify-center rounded-xl bg-accent/15 text-accent shadow-sm">
            <ShieldCheck size={24} />
          </div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Sign In</h1>
          <p className="mt-1 text-xs text-muted">Enter your credentials to continue</p>
        </div>

        {/* Error Alert */}
        {error && (
          <div className="mb-5 rounded-lg border border-bad/30 bg-bad/10 p-3 text-xs text-bad leading-relaxed">
            {error}
          </div>
        )}

        {/* Login Form */}
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-ink">Username</label>
            <div className="relative">
              <User
                size={15}
                className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted"
              />
              <input
                type="text"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="Username"
                autoComplete="username"
                className="w-full rounded-lg border border-border bg-base py-2.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none transition focus:border-accent"
              />
            </div>
          </div>

          <div>
            <label className="mb-1.5 block text-xs font-semibold text-ink">Password</label>
            <div className="relative">
              <Lock
                size={15}
                className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted"
              />
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Password"
                autoComplete="current-password"
                className="w-full rounded-lg border border-border bg-base py-2.5 pl-9 pr-3 text-xs text-ink placeholder:text-muted outline-none transition focus:border-accent"
              />
            </div>
          </div>

          <button
            type="submit"
            disabled={loading}
            className="group mt-2 flex w-full items-center justify-center gap-2 rounded-lg bg-accent px-4 py-2.5 text-xs font-bold text-base shadow-sm transition hover:bg-accent/90 disabled:opacity-60"
          >
            {loading ? (
              <div className="flex items-center gap-2">
                <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-base border-t-transparent" />
                <span>Signing in…</span>
              </div>
            ) : (
              <>
                <span>Sign in</span>
                <ArrowRight size={14} className="transition-transform group-hover:translate-x-0.5" />
              </>
            )}
          </button>
        </form>
      </div>
    </div>
  );
}