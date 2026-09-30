"use client";

import { useEffect, useMemo, useState } from "react";
import { Lock, RefreshCw, Save, ShieldCheck } from "lucide-react";
import { api, AppPolicy } from "@/lib/api";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { can, DENIED_HINT } from "@/lib/permissions";

const toLines = (text: string): string[] =>
  text
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean);

const when = (iso: string) =>
  new Date(iso).toLocaleString([], { month: "short", day: "numeric", year: "numeric", hour: "2-digit", minute: "2-digit" });

export default function PoliciesPage() {
  const [policy, setPolicy] = useState<AppPolicy | null>(null);
  const [history, setHistory] = useState<AppPolicy[]>([]);
  const [requiredText, setRequiredText] = useState("");
  const [blockedText, setBlockedText] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  const canEdit = can("editPolicy");

  const load = async () => {
    setLoading(true);
    try {
      const [active, hist] = await Promise.all([api.policy(), api.policyHistory()]);
      setPolicy(active);
      setHistory(hist);
      setRequiredText(active.requiredApps.join("\n"));
      setBlockedText(active.blockedApps.join("\n"));
    } catch (e) {
      setMsg({ text: e instanceof Error ? e.message : "Could not load the policy.", ok: false });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const dirty = useMemo(() => {
    if (!policy) return false;
    const same = (a: string[], b: string[]) =>
      a.length === b.length && a.every((v, i) => v === b[i]);
    return !(same(toLines(requiredText), policy.requiredApps) && same(toLines(blockedText), policy.blockedApps));
  }, [policy, requiredText, blockedText]);

  async function save() {
    setConfirmOpen(false);
    setSaving(true);
    setMsg(null);
    try {
      const created = await api.updatePolicy(toLines(requiredText), toLines(blockedText));
      setMsg({
        text: `Saved as policy v${created.version}. The next posture checks will use it.`,
        ok: true,
      });
      await load();
    } catch (e) {
      // A 403 here means the signed-in user is not an ADMIN; the server is the real control.
      setMsg({ text: e instanceof Error ? e.message : "Could not save the policy.", ok: false });
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">Application Policy</h1>
          <p className="mt-1 text-xs text-muted">
            Required and blocked software, used by every posture check. Each save creates a new version, and every
            assessment records the version it was judged against.
          </p>
        </div>
        <button
          onClick={load}
          disabled={loading}
          className="flex items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:border-accent/40 disabled:opacity-50"
        >
          <RefreshCw size={13} className={loading ? "animate-spin text-accent" : "text-muted"} />
          <span>Refresh</span>
        </button>
      </div>

      {!canEdit && (
        <div className="rounded-lg border border-border bg-panel2/60 px-4 py-2.5 text-xs text-muted">
          You have read-only access to this page. Only administrators can change the policy.
        </div>
      )}

      {msg && (
        <div
          className={`rounded-lg border px-4 py-2.5 text-xs font-medium ${
            msg.ok ? "border-good/30 bg-good/10 text-good" : "border-bad/30 bg-bad/10 text-bad"
          }`}
        >
          {msg.text}
        </div>
      )}

      {policy && (
        <div className="panel p-5">
          <div className="mb-4 flex items-center justify-between">
            <div className="text-sm font-semibold text-ink">
              Active policy <span className="font-mono text-accent">v{policy.version}</span>
            </div>
            <div className="text-[11px] text-muted">
              {policy.createdBy ? `Set by ${policy.createdBy} · ` : ""}
              {when(policy.createdAt)}
            </div>
          </div>

          <div className="grid grid-cols-1 gap-5 md:grid-cols-2">
            <div>
              <label className="mb-1.5 flex items-center gap-2 text-xs font-bold text-ink">
                <ShieldCheck size={14} className="text-good" />
                Required applications
              </label>
              <textarea
                value={requiredText}
                onChange={(e) => setRequiredText(e.target.value)}
                readOnly={!canEdit}
                rows={8}
                placeholder="One application per line"
                className="w-full rounded-lg border border-border bg-base p-3 font-mono text-xs text-ink outline-none placeholder:text-muted focus:border-accent read-only:opacity-70"
              />
              <p className="mt-1 text-[11px] text-muted">
                A device missing any of these is NON_COMPLIANT.
              </p>
            </div>

            <div>
              <label className="mb-1.5 flex items-center gap-2 text-xs font-bold text-ink">
                <Lock size={14} className="text-bad" />
                Blocked applications
              </label>
              <textarea
                value={blockedText}
                onChange={(e) => setBlockedText(e.target.value)}
                readOnly={!canEdit}
                rows={8}
                placeholder="One application per line"
                className="w-full rounded-lg border border-border bg-base p-3 font-mono text-xs text-ink outline-none placeholder:text-muted focus:border-accent read-only:opacity-70"
              />
              <p className="mt-1 text-[11px] text-muted">
                A device with any of these installed is NON_COMPLIANT.
              </p>
            </div>
          </div>

          <p className="mt-4 text-[11px] text-muted">
            Matching is a case-insensitive substring of the installed program name. The characters <code>|</code> and{" "}
            <code>&quot;</code> are not allowed. Changing the policy never contacts Cisco ISE.
          </p>

          <div className="mt-4 flex justify-end">
            <button
              onClick={() => setConfirmOpen(true)}
              disabled={!dirty || saving || !canEdit}
              title={!canEdit ? DENIED_HINT : undefined}
              className="flex items-center gap-1.5 rounded-lg bg-accent px-4 py-2 text-xs font-semibold text-base transition hover:bg-accent/90 disabled:opacity-50"
            >
              <Save size={13} />
              <span>{saving ? "Saving…" : "Save as new version"}</span>
            </button>
          </div>
        </div>
      )}

      <div className="panel overflow-hidden">
        <div className="border-b border-border/60 px-5 py-3 text-xs font-semibold uppercase tracking-wider text-muted">
          Version history
        </div>
        <div className="divide-y divide-border/40 text-xs">
          {history.length === 0 && <div className="py-8 text-center text-muted">No versions yet.</div>}
          {history.map((h) => (
            <div key={h.id} className="flex flex-col gap-1 px-5 py-3">
              <div className="flex items-center justify-between">
                <span className="font-mono font-semibold text-ink">
                  v{h.version}
                  {policy?.id === h.id && (
                    <span className="ml-2 rounded-full bg-accent/15 px-2 py-0.5 font-sans text-[10px] text-accent">
                      active
                    </span>
                  )}
                </span>
                <span className="text-muted">
                  {h.createdBy ?? "unknown"} · {when(h.createdAt)}
                </span>
              </div>
              <div className="text-muted">
                <span className="text-good">Required:</span> {h.requiredApps.join(", ") || "none"}
                {"  ·  "}
                <span className="text-bad">Blocked:</span> {h.blockedApps.join(", ") || "none"}
              </div>
            </div>
          ))}
        </div>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Save new application policy"
        message="This creates a new policy version. Posture checks that run from now on use it, so devices may change between compliant and non-compliant at their next check."
        onConfirm={save}
        onCancel={() => setConfirmOpen(false)}
      />
    </div>
  );
}