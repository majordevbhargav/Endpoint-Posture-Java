// New API calls for S13 (paged inventory) and X1 (ISE sharing rule).
// Needs ONE edit in lib/api.ts: change `async function request<T>` to `export async function request<T>`
// so these calls share its token handling and error messages.
import { request, AppRow, PortRow, PageResponse } from "@/lib/api";

export interface AppSummary {
  evaluatedEndpoints: number;
  totalApps: number;
  compliant: number;
  nonCompliant: number;
}

export interface PortSummary {
  evaluatedEndpoints: number;
  totalPorts: number;
  reachable: number;
  blocked: number;
}

export type SharingScope = "NONE" | "CONNECTED" | "CONNECTED_NON_COMPLIANT";

export interface SharingRule {
  id: string;
  version: number;
  scope: SharingScope;
  active: boolean;
  createdBy: string | null;
  createdAt: string;
}

function qs(p: Record<string, string | number | undefined>): string {
  const out = new URLSearchParams();
  for (const [k, v] of Object.entries(p)) {
    if (v !== undefined && v !== "" && v !== "ALL") out.set(k, String(v));
  }
  return out.toString();
}

export const apiExtra = {
  applicationsPage: (p: { page?: number; size?: number; q?: string; status?: string } = {}) =>
    request<PageResponse<AppRow>>(
      `/api/v1/applications/page?${qs({ page: p.page ?? 0, size: p.size ?? 25, q: p.q?.trim(), status: p.status })}`,
    ),

  applicationsSummary: () => request<AppSummary>("/api/v1/applications/summary"),

  portsPage: (p: { page?: number; size?: number; q?: string; reach?: string } = {}) =>
    request<PageResponse<PortRow>>(
      `/api/v1/ports/page?${qs({ page: p.page ?? 0, size: p.size ?? 25, q: p.q?.trim(), reach: p.reach })}`,
    ),

  portsSummary: () => request<PortSummary>("/api/v1/ports/summary"),

  sharingRule: () => request<SharingRule>("/api/v1/integrations/ise/sharing-rule"),

  sharingRuleHistory: () => request<SharingRule[]>("/api/v1/integrations/ise/sharing-rule/history"),

  updateSharingRule: (scope: SharingScope) =>
    request<SharingRule>("/api/v1/integrations/ise/sharing-rule", {
      method: "PUT",
      body: JSON.stringify({ scope }),
    }),
};