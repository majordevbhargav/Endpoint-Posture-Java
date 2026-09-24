"use client";
export function ConfirmDialog({
  open, title, message, danger, onConfirm, onCancel,
}: { open: boolean; title: string; message: string; danger?: boolean; onConfirm: () => void; onCancel: () => void }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs">
      <div className="w-full max-w-sm rounded-2xl border border-border bg-panel p-6 shadow-2xl">
        <h2 className="text-sm font-bold text-ink">{title}</h2>
        <p className="mt-2 text-xs text-muted leading-relaxed">{message}</p>
        <div className="mt-5 flex justify-end gap-2">
          <button onClick={onCancel} className="rounded-lg border border-border bg-panel px-3 py-1.5 text-xs font-medium text-ink hover:bg-ink/[0.04]">Cancel</button>
          <button onClick={onConfirm} className={`rounded-lg px-3 py-1.5 text-xs font-semibold ${danger ? "bg-bad text-white hover:bg-bad/90" : "bg-accent text-base hover:bg-accent/90"}`}>Confirm</button>
        </div>
      </div>
    </div>
  );
}