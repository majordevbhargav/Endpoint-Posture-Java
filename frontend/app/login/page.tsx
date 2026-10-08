"use client";

import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle, Eye, EyeOff, Loader2, LogIn, ShieldCheck } from "lucide-react";
import { api, getToken, setToken } from "@/lib/api";
import { setCurrentUser } from "@/lib/auth";
import { isTokenValid } from "@/lib/session";
import { clearFetchCache } from "@/lib/useCachedFetch";
import { ThemeToggle } from "@/components/layout/ThemeToggle";

export default function LoginPage() {
  const router = useRouter();
  const [checking, setChecking] = useState(true); // avoids flashing the form for a signed-in user
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (isTokenValid(getToken())) {
      router.replace("/overview");
    } else {
      setChecking(false);
    }
  }, [router]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await api.login(username.trim(), password);
      clearFetchCache(); // never show the previous user's cached data
      setToken(res.token);
      setCurrentUser({ username: res.username, role: res.role });
      router.push("/overview");
    } catch (err) {
      setPassword(""); // do not leave a rejected password sitting in the field
      setError(err instanceof Error ? err.message : "Sign in failed. Try again.");
      setBusy(false);
    }
  }

  if (checking) {
    return (
      <div className="flex min-h-screen items-center justify-center text-xs text-muted">
        <Loader2 size={14} className="mr-2 animate-spin text-accent" />
        Loading…
      </div>
    );
  }

  const canSubmit = !busy && username.trim().length > 0 && password.length > 0;

  return (
    <main className="relative flex min-h-screen items-center justify-center bg-base p-4">
      <div className="absolute right-4 top-4">
        <ThemeToggle />
      </div>

      <div className="w-full max-w-sm">
        <div className="mb-6 flex flex-col items-center text-center">
          <div className="relative flex h-14 w-14 items-center justify-center rounded-2xl bg-accent/15 text-accent shadow-sm">
            <ShieldCheck size={28} />
            <span className="absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full border-2 border-base bg-good" />
          </div>
          <h1 className="mt-4 text-xl font-bold tracking-tight text-ink">PostureEngine</h1>
          <p className="mt-1 text-xs text-muted">Endpoint posture and compliance, with Cisco ISE</p>
        </div>

        <div className="panel p-6">
          <h2 className="text-sm font-semibold text-ink">Sign in</h2>

          {error && (
            <div
              role="alert"
              className="mt-4 flex items-start gap-2 rounded-lg border border-bad/30 bg-bad/10 p-3 text-xs text-bad"
            >
              <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <form onSubmit={onSubmit} className="mt-4 space-y-4" noValidate>
            <div>
              <label htmlFor="username" className="mb-1 block text-xs font-semibold text-ink">
                Username
              </label>
              <input
                id="username"
                name="username"
                type="text"
                autoComplete="username"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                autoFocus
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="w-full rounded-lg border border-border bg-base px-3 py-2 text-xs text-ink outline-none transition focus:border-accent"
              />
            </div>

            <div>
              <label htmlFor="password" className="mb-1 block text-xs font-semibold text-ink">
                Password
              </label>
              <div className="relative">
                <input
                  id="password"
                  name="password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  onKeyUp={(e) => setCapsLock(e.getModifierState("CapsLock"))}
                  onKeyDown={(e) => setCapsLock(e.getModifierState("CapsLock"))}
                  onBlur={() => setCapsLock(false)}
                  className="w-full rounded-lg border border-border bg-base py-2 pl-3 pr-9 text-xs text-ink outline-none transition focus:border-accent"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  aria-label={showPassword ? "Hide password" : "Show password"}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted transition hover:text-ink"
                  tabIndex={-1}
                >
                  {showPassword ? <EyeOff size={14} /> : <Eye size={14} />}
                </button>
              </div>
              {capsLock && <p className="mt-1.5 text-[11px] text-warn">Caps Lock is on.</p>}
            </div>

            <button
              type="submit"
              disabled={!canSubmit}
              className="flex w-full items-center justify-center gap-1.5 rounded-lg bg-accent px-3 py-2.5 text-xs font-semibold text-base transition hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {busy ? <Loader2 size={13} className="animate-spin" /> : <LogIn size={13} />}
              <span>{busy ? "Signing in…" : "Sign in"}</span>
            </button>
          </form>
        </div>

        <p className="mt-4 text-center text-[11px] text-muted">
          Accounts lock for a few minutes after repeated wrong passwords.
        </p>
      </div>
    </main>
  );
}