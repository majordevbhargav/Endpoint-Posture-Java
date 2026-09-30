"use client";

import { useEffect, useState } from "react";
import { RefreshCw, UserPlus } from "lucide-react";
import { api, UserView, UserRole } from "@/lib/api";
import { can } from "@/lib/permissions";
import { getCurrentUser } from "@/lib/auth";

const ROLES: UserRole[] = ["ADMIN", "OPERATOR", "ANALYST", "VIEWER"];

export default function UsersPage() {
  const [users, setUsers] = useState<UserView[] | null>(null);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [form, setForm] = useState({ username: "", password: "", role: "VIEWER" as UserRole });
  const me = getCurrentUser()?.username;

  const load = () => api.users().then(setUsers).catch((e) => setMsg({ text: e.message, ok: false }));
  useEffect(() => { load(); }, []);

  async function run(fn: () => Promise<unknown>, okText: string) {
    setMsg(null);
    try { await fn(); setMsg({ text: okText, ok: true }); await load(); }
    catch (e) { setMsg({ text: e instanceof Error ? e.message : "Failed", ok: false }); }
  }

  if (!can("manageUsers")) {
    return <div className="panel p-8 text-center text-xs text-muted">Only administrators can manage users.</div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Users &amp; Roles</h1>
          <p className="mt-1 text-xs text-muted">
            Role changes and disabling take effect on the user&apos;s next request, not when their token expires.
          </p>
        </div>
        <button onClick={load} className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink">
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
          <input placeholder="Username" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })}
            className="rounded-lg border border-border bg-base px-3 py-2 text-xs text-ink outline-none focus:border-accent" />
          <input type="password" placeholder="Password (12+ characters)" value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
            className="rounded-lg border border-border bg-base px-3 py-2 text-xs text-ink outline-none focus:border-accent" />
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as UserRole })}
            className="rounded-lg border border-border bg-panel px-3 py-2 text-xs text-ink outline-none focus:border-accent">
            {ROLES.map((r) => <option key={r}>{r}</option>)}
          </select>
          <button
            onClick={() => run(async () => { await api.createUser(form.username, form.password, form.role); setForm({ username: "", password: "", role: "VIEWER" }); }, "User created.")}
            className="rounded-lg bg-accent px-3 py-2 text-xs font-semibold text-base hover:bg-accent/90">Create</button>
        </div>
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
            {users?.map((u) => (
              <tr key={u.id}>
                <td className="px-4 py-3 font-semibold text-ink">{u.username}{u.username === me && <span className="ml-2 text-[10px] text-muted">(you)</span>}</td>
                <td className="px-4 py-3">
                  <select value={u.role} onChange={(e) => run(() => api.updateUser(u.id, { role: e.target.value as UserRole }), "Role updated.")}
                    className="rounded-lg border border-border bg-panel px-2 py-1 text-xs text-ink outline-none focus:border-accent">
                    {ROLES.map((r) => <option key={r}>{r}</option>)}
                  </select>
                </td>
                <td className={`px-4 py-3 font-medium ${u.enabled ? "text-good" : "text-muted"}`}>{u.enabled ? "Enabled" : "Disabled"}</td>
                <td className="px-4 py-3 text-right">
                  <button onClick={() => run(() => api.updateUser(u.id, { enabled: !u.enabled }), u.enabled ? "User disabled." : "User enabled.")}
                    className="mr-2 rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted hover:text-ink">
                    {u.enabled ? "Disable" : "Enable"}
                  </button>
                  <button onClick={() => {
                      const p = window.prompt(`New password for ${u.username} (12+ characters)`);
                      if (p) run(() => api.resetPassword(u.id, p), "Password reset.");
                    }}
                    className="rounded border border-border bg-panel px-2 py-1 text-[11px] text-muted hover:text-ink">Reset password</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}