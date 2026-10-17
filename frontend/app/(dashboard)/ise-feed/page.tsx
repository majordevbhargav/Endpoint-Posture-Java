"use client";

import { useCallback, useEffect, useState } from "react";
import { Eye, Info, RefreshCw, Save, Share2, ShieldCheck } from "lucide-react";
import { apiExtra, IseFeed, SharingRule, SharingScope } from "@/lib/apiExtra";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { PaginationBar } from "@/components/ui/PaginationBar";
import { can, DENIED_HINT } from "@/lib/permissions";

const SCOPES: { value: SharingScope; title: string; text: string }[] = [
  {
    value: "NONE",
    title: "Share nothing",
    text: "The feed is empty. ISE receives no posture facts from this platform. This is the default.",
  },
  {
    value: "CONNECTED",
    title: "All connected, assessed endpoints",
    text: "Every endpoint with a live ISE session and at least one posture result appears in the feed.",
  },
  {
    value: "CONNECTED_NON_COMPLIANT",
    title: "Connected endpoints that are not compliant",
    text: "Only connected endpoints whose latest result is NON_COMPLIANT or ERROR appear in the feed.",
  },
];

const scopeLabel = (s: SharingScope) => SCOPES.find((x) => x.value === s)?.title ?? s;

const when = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleString([], { month: "short", day: "numeric", year: "numeric", hour: "2-digit", minute: "2-digit" })
    : "—";

export default function IseFeedPage() {
  const [rule, setRule] = useState<SharingRule | null>(null);
  const [history, setHistory] = useState<SharingRule[]>([]);
  const [selected, setSelected] = useState<SharingScope>("NONE");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  const [feed, setFeed] = useState<IseFeed | null>(null);
  const [feedPage, setFeedPage] = useState(1);
  const [feedSize, setFeedSize] = useState(25);
  const [feedLoading, setFeedLoading] = useState(false);

  // UI convenience only: the backend @PreAuthorize rule is the real control.
  const isAdmin = can("editSharingRule");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [active, hist] = await Promise.all([apiExtra.sharingRule(), apiExtra.sharingRuleHistory()]);
      setRule(active);
      setHistory(hist);
      setSelected(active.scope);
    } catch (e) {
      setMsg({ text: e instanceof Error ? e.message : "Could not load the sharing rule.", ok: false });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const loadFeed = useCallback(async (page: number, size: number) => {
    setFeedLoading(true);
    try {
      setFeed(await apiExtra.iseFeedPreview({ page: page - 1, size }));
    } catch (e) {
      setMsg({ text: e instanceof Error ? e.message : "Could not load the feed preview.", ok: false });
    } finally {
      setFeedLoading(false);
    }
  }, []);

  async function save() {
    setConfirmOpen(false);
    setSaving(true);
    setMsg(null);
    try {
      const created = await apiExtra.updateSharingRule(selected);
      setMsg({
        text:
          created.id === rule?.id
            ? "That scope is already active. No new version was created."
            : `Saved as sharing rule v${created.version}. ISE sees it on its next pull.`,
        ok: true,
      });
      setFeed(null); // the preview belonged to the old rule
      await load();
    } catch (e) {
      setMsg({ text: e instanceof Error ? e.message : "Could not save the sharing rule.", ok: false });
    } finally {
      setSaving(false);
    }
  }

  const dirty = rule !== null && selected !== rule.scope;
  const feedPageCount = feed ? Math.max(1, Math.ceil(feed.total / feedSize)) : 1;
  const from = feed && feed.total > 0 ? (feedPage - 1) * feedSize + 1 : 0;
  const to = feed ? Math.min(feed.total, feedPage * feedSize) : 0;

  return (
    <div className="space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-ink">ISE Sharing Feed</h1>
          <p className="mt-1 text-xs text-muted">
            A standing, admin-approved rule decides which posture facts Cisco ISE may pull. ISE&apos;s own
            Authorization Policy decides what to do with them.
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

      <div className="rounded-xl border border-accent/30 bg-accent/10 p-4 text-xs">
        <div className="flex items-center gap-2 font-semibold text-accent">
          <Info size={15} />
          <span>What this does and does not do</span>
        </div>
        <p className="mt-1 text-muted">
          The feed carries posture <strong>facts</strong> only (status, failed checks, hardware band, risk level). It
          never carries an enforcement instruction. <strong>Restrict and Clear are never covered by a rule</strong>;
          they stay per-device operator actions on the endpoint page. Collecting a result never shares it by itself:
          only what the active rule covers can appear in the feed.
        </p>
      </div>

      {!isAdmin && (
        <div className="rounded-lg border border-border bg-panel2/60 px-4 py-2.5 text-xs text-muted">
          You have read-only access to this page. Only administrators can change the rule or preview the feed.
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

      {/* Active rule + scope selector */}
      {rule && (
        <div className="panel p-5">
          <div className="mb-4 flex flex-col justify-between gap-2 sm:flex-row sm:items-center">
            <div className="flex items-center gap-2 text-sm font-semibold text-ink">
              <ShieldCheck size={15} className="text-accent" />
              <span>
                Active rule <span className="font-mono text-accent">v{rule.version}</span>
              </span>
              <span className="rounded-full bg-accent/15 px-2 py-0.5 text-[10px] font-semibold text-accent">
                {scopeLabel(rule.scope)}
              </span>
            </div>
            <div className="text-[11px] text-muted">
              {rule.createdBy ? `Approved by ${rule.createdBy} · ` : ""}
              {when(rule.createdAt)}
            </div>
          </div>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            {SCOPES.map((s) => {
              const active = selected === s.value;
              return (
                <button
                  key={s.value}
                  type="button"
                  disabled={!isAdmin}
                  onClick={() => setSelected(s.value)}
                  className={`rounded-xl border p-4 text-left text-xs transition disabled:cursor-not-allowed ${
                    active
                      ? "border-accent bg-accent/10"
                      : "border-border bg-base hover:border-accent/40 disabled:hover:border-border"
                  }`}
                >
                  <div className={`font-bold ${active ? "text-accent" : "text-ink"}`}>{s.title}</div>
                  <div className="mt-1.5 leading-relaxed text-muted">{s.text}</div>
                  {rule.scope === s.value && (
                    <div className="mt-2 text-[10px] font-semibold uppercase tracking-wider text-good">Currently active</div>
                  )}
                </button>
              );
            })}
          </div>

          <div className="mt-4 flex items-center justify-between gap-3">
            <p className="text-[11px] text-muted">
              Each change creates a new version attributed to you. Selecting the active scope again changes nothing.
            </p>
            <button
              onClick={() => setConfirmOpen(true)}
              disabled={!dirty || saving || !isAdmin}
              title={!isAdmin ? DENIED_HINT : undefined}
              className="flex flex-shrink-0 items-center gap-1.5 rounded-lg bg-accent px-4 py-2 text-xs font-semibold text-base transition hover:bg-accent/90 disabled:opacity-50"
            >
              <Save size={13} />
              <span>{saving ? "Saving…" : "Approve rule"}</span>
            </button>
          </div>
        </div>
      )}

      {/* ISE setup hints */}
      <div className="panel p-5">
        <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-ink">
          <Share2 size={15} className="text-accent" />
          <span>How ISE pulls the feed</span>
        </div>
        <div className="grid grid-cols-1 gap-3 text-xs md:grid-cols-3">
          <div className="rounded-lg bg-base/70 p-3">
            <div className="text-muted">URL (pxGrid Direct)</div>
            <div className="mt-1 break-all font-mono font-semibold text-ink">/api/v1/integrations/ise/endpoints</div>
          </div>
          <div className="rounded-lg bg-base/70 p-3">
            <div className="text-muted">Authentication</div>
            <div className="mt-1 font-semibold text-ink">HTTP Basic, scoped feed credential</div>
            <div className="mt-0.5 text-[11px] text-muted">
              Set by <code>ISE_FEED_USER</code> / <code>ISE_FEED_PASSWORD</code>. It opens this route only.
            </div>
          </div>
          <div className="rounded-lg bg-base/70 p-3">
            <div className="text-muted">Paging</div>
            <div className="mt-1 font-semibold text-ink">
              <code>page</code> from 0, <code>size</code> up to 50,000
            </div>
            <div className="mt-0.5 text-[11px] text-muted">Check ISE&apos;s payload limits before a large fleet.</div>
          </div>
        </div>
      </div>

      {/* Feed preview */}
      <div className="panel overflow-hidden">
        <div className="flex flex-col justify-between gap-3 border-b border-border/60 px-5 py-3 sm:flex-row sm:items-center">
          <div>
            <div className="text-xs font-semibold uppercase tracking-wider text-muted">Feed preview</div>
            <div className="mt-0.5 text-[11px] text-muted">
              Exactly what ISE would receive under the active rule. Each preview is audited as a feed fetch (counts only,
              never the payload).
            </div>
          </div>
          <button
            onClick={() => {
              setFeedPage(1);
              loadFeed(1, feedSize);
            }}
            disabled={!isAdmin || feedLoading}
            title={!isAdmin ? DENIED_HINT : undefined}
            className="flex flex-shrink-0 items-center gap-1.5 rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink transition hover:border-accent/40 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <Eye size={13} className={feedLoading ? "animate-pulse text-accent" : "text-muted"} />
            <span>{feed ? "Reload preview" : "Load preview"}</span>
          </button>
        </div>

        {feed && (
          <div className="border-b border-border/60 px-5 py-2 text-[11px] text-muted">
            Rule <span className="font-mono text-ink">v{feed.ruleVersion}</span> · {scopeLabel(feed.scope)} ·{" "}
            <span className="font-semibold text-ink">{feed.total}</span> endpoint{feed.total === 1 ? "" : "s"} shared ·
            generated {when(feed.generatedAt)}
          </div>
        )}

        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] border-collapse text-left text-xs">
            <thead>
              <tr className="border-b border-border bg-panel2/40 text-[11px] font-semibold text-muted">
                <th className="px-4 py-3">MAC address</th>
                <th className="px-4 py-3">Posture</th>
                <th className="px-4 py-3">Failed checks</th>
                <th className="px-4 py-3">Connected</th>
                <th className="px-4 py-3">Hardware band</th>
                <th className="px-4 py-3">Security risk</th>
                <th className="px-4 py-3">Last checked</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {feed === null && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    {isAdmin ? "Click “Load preview” to see the current feed." : "Only administrators can preview the feed."}
                  </td>
                </tr>
              )}
              {feed !== null && feed.endpoints.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-12 text-center text-muted">
                    {feed.scope === "NONE"
                      ? "The active rule shares nothing, so the feed is empty."
                      : "No endpoint is covered by the active rule right now."}
                  </td>
                </tr>
              )}
              {(feed?.endpoints ?? []).map((e) => (
                <tr key={e.mac} className="transition hover:bg-ink/[0.02]">
                  <td className="px-4 py-3 font-mono text-ink">{e.mac}</td>
                  <td className="px-4 py-3">
                    <StatusBadge value={e.postureStatus} />
                  </td>
                  <td className="px-4 py-3 text-muted">{e.failedChecks}</td>
                  <td className="px-4 py-3 text-muted">{e.connected ? "Yes" : "No"}</td>
                  <td className="px-4 py-3">
                    {e.hardwareBand ? <StatusBadge value={e.hardwareBand} /> : <span className="text-muted">—</span>}
                  </td>
                  <td className="px-4 py-3">
                    {e.securityRiskLevel ? <StatusBadge value={e.securityRiskLevel} /> : <span className="text-muted">—</span>}
                  </td>
                  <td className="px-4 py-3 text-muted">{when(e.lastChecked)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {feed !== null && (
          <PaginationBar
            page={feedPage}
            pageCount={feedPageCount}
            pageSize={feedSize}
            total={feed.total}
            from={from}
            to={to}
            setPage={(p) => {
              const next = Math.max(1, Math.min(p, feedPageCount));
              setFeedPage(next);
              loadFeed(next, feedSize);
            }}
            setPageSize={(n) => {
              setFeedSize(n);
              setFeedPage(1);
              loadFeed(1, n);
            }}
          />
        )}
      </div>

      {/* Version history */}
      <div className="panel overflow-hidden">
        <div className="border-b border-border/60 px-5 py-3 text-xs font-semibold uppercase tracking-wider text-muted">
          Rule history
        </div>
        <div className="divide-y divide-border/40 text-xs">
          {history.length === 0 && <div className="py-8 text-center text-muted">No versions yet.</div>}
          {history.map((h) => (
            <div key={h.id} className="flex flex-col gap-1 px-5 py-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-2">
                <span className="font-mono font-semibold text-ink">v{h.version}</span>
                <span className="text-muted">{scopeLabel(h.scope)}</span>
                {h.active && (
                  <span className="rounded-full bg-accent/15 px-2 py-0.5 text-[10px] font-semibold text-accent">active</span>
                )}
              </div>
              <span className="text-muted">
                {h.createdBy ?? "unknown"} · {when(h.createdAt)}
              </span>
            </div>
          ))}
        </div>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Approve new sharing rule"
        message={`This makes “${scopeLabel(selected)}” the standing rule. On its next pull, ISE receives posture facts for every endpoint it covers. Nothing is restricted or cleared by this change, and the approval is recorded under your name.`}
        onConfirm={save}
        onCancel={() => setConfirmOpen(false)}
      />
    </div>
  );
}