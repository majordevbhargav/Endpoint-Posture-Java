"use client";

import { useEffect, useRef, useState } from "react";
import { RefreshCw, Trash2, UserPlus } from "lucide-react";
import { api, UserView, UserRole } from "@/lib/api";
import { can } from "@/lib/permissions";
import { getCurrentUser } from "@/lib/auth";

const ROLES: UserRole[] = ["ADMIN", "OPERATOR", "ANALYST", "VIEWER"];
const SELF_HINT = "You cannot change your own role, disable or delete yourself";

export default function UsersPage() {
  const [users, setUsers] = useState<UserView[] | null>(null);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [form, setForm] = useState({ username: "", password: "", role: "VIEWER" as UserRole });
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const me = getCurrentUser()?.username;

  function flash(text: string, ok: boolean) {
    if (timer.current) clearTimeout(timer.current);
    setMsg({ text, ok });
    timer.current = setTimeout(() => setMsg(null), 5000);
  }

  const load = () =>
    api.users().then(setUsers).catch((e) => flash(e.message, false));

  useEffect(() => {
    load();
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function run(fn: () => Promise<unknown>, okText: string) {
    setMsg(null);
    try {
      await fn();
      flash(okText, true);
    } catch (e) {
      flash(e instanceof Error ? e.message : "Failed", false);
    }
    await load(); // always resync, so a refused change snaps back to the real value
  }

  function refresh() {
    setMsg(null);
    load();
  }

  if (!can("manageUsers")) {
    return <div className="panel p-8 text-center text-xs text-muted">Only administrators can manage users.</div>;
  }

  const formValid = form.username.trim().length >= 3 && form.password.length >= 12;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Users &amp; Roles</h1>
          <p className="mt-1 text-xs text-muted">
            Role changes and disabling take effect on the user&apos;s next request, not when their token expires.
          </p>
        </div>
        <button onClick={refresh} className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink">
          <RefreshCw size={13} className="text-muted" /><span>Refresh</span>
        </button>
      </div>

      {msg && (
        <div className={`rounded-lg border px-4 py-2.5 text-xs font-medium ${msg.ok ? "border-good/30 bg-good/10 text-good" : "border-bad/30 bg-bad/10 text-bad"}`}>
          {msg.text}
        </div>
      )}

      <div className="panel p-4">
        <div className="mb-3 flex items-center gap-2 text-xs font-bold text-ink"><UserPlus size={14} className="text-accent" />New user</div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
          <input placeholder="Username (3+ characters)" value={form.username}
            onChange={(e) => setForm({ ...form, username: e.target.value })}
            className="rounded-lg border border-border bg-base px-3 py-2 text-xs text-ink outline-none focus:border-accent" />
          <input type="password" placeholder="Password (12+ characters)" value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
            className="rounded-lg border border-border bg-base px-3 py-2 text-xs text-ink outline-none focus:border-accent" />
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as UserRole })}
            className="rounded-lg border border-border bg-panel px-3 py-2 text-xs text-ink outline-none focus:border-accent">
            {ROLES.map((r) => <option key={r}>{r}</option>)}
          </select>
          <button
            disabled={!formValid}
            onClick={() => run(async () => {
              await api.createUser(form.username.trim(), form.password, form.role);
              setForm({ username: "", password: "", role: "VIEWER" });
            }, "User created.")}
            className="rounded-lg bg-accent px-3 py-2 text-xs font-semibold text-base hover:bg-accent/90 disabled:opacity-50">
            Create
          </button>
        </div>
        {form.password.length > 0 && form.password.length < 12 && (
          <p className="mt-2 text-[11px] text-warn">Password needs {12 - form.password.length} more character(s).</p>
        )}
      </div>

      <div className="panel overflow-hidden">
        <table className="w-full border-collapse text-left text-xs">
          <thead>
            <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
              <th className="px-4 py-3">Username</th><th className="px-4 py-3">Role</th>
              <th className="px-4 py-3">Status</th><th className="px-4 py-3 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border/40">
            {users === null && <tr><td colSpan={4} className="py-10 text-center text-muted">Loading…</td></tr>}
            {users?.map((u) => {
              const self = u.username === me;
              return (
                <tr key={u.id}>
                  <td className="px-4 py-3 font-semibold text-ink">
                    {u.username}{self && <span className="ml-2 text-[10px] text-muted">(you)</span>}
                  </td>
                  <td className="px-4 py-3">
                    <select value={u.role} disabled={self} title={self ? SELF_HINT : undefined}
                      onChange={(e) => run(() => api.updateUser(u.id, { role: e.target.value as UserRole }), "Role updated.")}
                      className="rounded-lg border border-border bg-panel px-2 py-1 text-xs text-ink outline-none focus:border-accent disabled:opacity-50">
                      {ROLES.map((r) => <option key={r}>{r}</option>)}
                    </select>
                  </td>
                  <td className={`px-4 py-3 font-medium ${u.enabled ? "text-good" : "text-muted"}`}>{u.enabled ? "Enabled" : "Disabled"}</td>
                  <td className="px-4 py-3 text-right">
                    <button disabled={self} title={self ? SELF_HINT : undefined}
                      onClick={() => run(() => api.updateUser(u.id, { enabled: !u.enabled }), u.enabled ? "User disabled." : "User enabled.")}
                      className="mr-2 rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted hover:text-ink disabled:opacity-40">
                      {u.enabled ? "Disable" : "Enable"}
                    </button>
                    <button onClick={() => {
                        const p = window.prompt(`New password for ${u.username} (12+ characters)`);
                        if (p) run(() => api.resetPassword(u.id, p), "Password reset.");
                      }}
                      className="mr-2 rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted hover:text-ink">
                      Reset password
                    </button>
                    <button disabled={self} title={self ? SELF_HINT : "Delete user"}
                      onClick={() => {
                        if (window.confirm(`Delete user "${u.username}"? This cannot be undone. Their past audit entries are kept.`)) {
                          run(() => api.deleteUser(u.id), "User deleted.");
                        }
                      }}
                      className="rounded border border-bad/30 bg-bad/10 px-2 py-1 text-[11px] text-bad hover:bg-bad/20 disabled:opacity-40">
                      <Trash2 size={11} className="inline" />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}