export interface EndpointResponse {
  id: string;
  macAddress: string;
  ipAddress: string | null;
  hostname: string | null;
  osName: string | null;
  osVersion: string | null;
  connected: boolean;
  lastSeenAt: string;
}

export type AssessmentStatus = "COMPLIANT" | "NON_COMPLIANT" | "ERROR";

export interface CheckResultResponse {
  id: string;
  checkType: string;
  status: AssessmentStatus;
  details: Record<string, unknown> | null;
  createdAt: string;
}

export interface AssessmentResponse {
  id: string;
  endpointId: string;
  jobId: string | null;
  status: AssessmentStatus;
  detail: string | null;
  startedAt: string;
  completedAt: string | null;
  checks: CheckResultResponse[];
}

export type HardwareBand = "HEALTHY" | "WARNING" | "DEGRADED" | "CRITICAL";

export interface HardwareHealthResponse {
  id: string;
  endpointId: string;
  jobId: string | null;
  manufacturer: string | null;
  model: string | null;
  serialNumber: string | null;
  biosVersion: string | null;
  // Null when the run failed and no earlier good run exists (never zero).
  cpuScore: number | null;
  memoryScore: number | null;
  storageScore: number | null;
  batteryScore: number | null;
  overallScore: number | null;
  overallBand: HardwareBand | null;
  hardwareEventCount: number | null;
  warrantyStatus: string | null;
  warrantyDaysRemaining: number | null;
  collectedAt: string;
  succeeded: boolean;
  errorMessage: string | null;
  // Set on the "latest" endpoints when a newer attempt failed after this (successful) run.
  lastAttemptFailedAt: string | null;
  lastAttemptError: string | null;
  recommendations: { priority: string; area: string; action: string }[];
}

export type JobType = "POSTURE_CHECK" | "HARDWARE_CHECK";
export type JobStatus = "QUEUED" | "RUNNING" | "COMPLETE" | "FAILED";

export interface JobResponse {
  id: string;
  endpointId: string;
  macAddress: string;
  jobType: JobType;
  status: JobStatus;
  priority: number;
  attemptCount: number;
  maxAttempts: number;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface IseActionAudit {
  id: string;
  endpointId: string;
  actionType: "SHARE_POSTURE" | "RESTRICT" | "CLEAR_RESTRICTION";
  operator: string | null;
  succeeded: boolean;
  detail: string | null;
  occurredAt: string;
}

export interface IseActionState {
  endpointId: string;
  actionType: "RESTRICT" | "CLEAR_RESTRICTION" | "SHARE_POSTURE";
  succeeded: boolean;
  operator: string | null;
  detail: string | null;
  occurredAt: string;
}

export interface IseStatus {
  reachable: boolean;
  lastSuccessAt: string | null;
  lastError: string | null;
  /** Seconds between ISE session polls (app.ise.session-poll-interval-ms). */
  pollIntervalSeconds?: number;
  /** "ATTRIBUTE" or "ANC" (app.ise.enforcement-mode). */
  enforcementMode?: string;
}

export interface SessionEvent {
  id: string;
  eventType: "CONNECTED" | "DISCONNECTED";
  ipAddress: string | null;
  eventAt: string;
}

export interface DashboardSummary {
  total: number;
  connected: number;
  notConnected: number;
  compliant: number;
  nonCompliant: number;
  error: number;
  unassessed: number;
  stale: number;
}

export interface TrendPoint {
  date: string;
  assessed: number;
  compliantPercent: number | null;
}

export interface CategoryRate {
  checkType: string;
  total: number;
  passing: number;
  passPercent: number;
}

/** Matches InventoryController.AppRow. */
export interface AppRow {
  name: string;
  version?: string | null;
  publisher?: string | null;
  hostname?: string | null;
  macAddress: string;
  status?: string | null;
  summary?: string | null;
}

/** Matches InventoryController.PortRow. */
export interface PortRow {
  port: number;
  process?: string | null;
  pid?: number | null;
  reachable?: boolean | null;
  hostname?: string | null;
  macAddress: string;
  status?: string | null;
}

/** One version of the application policy (required / blocked apps). */
export interface AppPolicy {
  id: string;
  name: string;
  version: number;
  requiredApps: string[];
  blockedApps: string[];
  createdBy: string | null;
  createdAt: string;
}

export interface SystemHealth {
  status: "UP" | "DEGRADED" | "DOWN";
  checkedAt: string;
  database: { reachable: boolean; error: string | null };
  ise: { reachable: boolean; lastSuccessAt: string | null; lastError: string | null };
  queue: {
    queued: number;
    running: number;
    complete: number;
    failed: number;
    oldestQueuedAgeSeconds: number | null;
    failedLast24h: number;
  };
  workers: { enabled: boolean; threads: number };
  warnings: string[];
}

export type UserRole = "ADMIN" | "OPERATOR" | "ANALYST" | "VIEWER";

/** Matches UserController.UserView. */
export interface UserView {
  id: string;
  username: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
  locked: boolean;
}


// ── Warranty types ─────────────────────────────────────────────────────────────
export interface WarrantyView {
  id: string;
  serialNumber: string;
  vendor: string;
  expiresOn: string; // ISO date yyyy-MM-dd
  productName: string | null;
  daysRemaining: number;
  status: "COVERED" | "EXPIRING_SOON" | "EXPIRED";
  source: string;
  uploadedBy: string;
  uploadedAt: string;
}

export interface WarrantyUploadResult {
  savedCount: number;
  rowErrors: string[];
}
const TOKEN_KEY = "vece_token";

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const res = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  });

  if (res.status === 401) {
    // A wrong password on the login form is not an expired session: do not
    // clear the token or reload the page (a reload closes the dev HMR socket
    // and wipes the error message).
    if (path === "/api/v1/auth/login") {
      throw new Error(
        "Invalid username or password. Accounts are locked for a few minutes after repeated failed attempts."
      );
    }
    clearToken();
    if (typeof window !== "undefined") window.location.href = "/login";
    throw new Error("Unauthorized");
  }

  if (res.status === 403) {
    throw new Error("Your role does not allow this action");
  }

  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? body?.error ?? `Request failed: ${res.status}`);
  }

  // 204 No Content (for example "no hardware report yet") has no body to parse.
  if (res.status === 204) return null as T;

  return res.json();
}

export const api = {
  login: (username: string, password: string) =>
    request<{ token: string; username: string; role: string }>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),

  listEndpoints: () => request<EndpointResponse[]>("/api/v1/endpoints"),
  getEndpoint: (id: string) => request<EndpointResponse>(`/api/v1/endpoints/${id}`),

  latestPosture: (id: string) => request<AssessmentResponse>(`/api/v1/endpoints/${id}/posture/latest`),
  latestPostureAll: () => request<AssessmentResponse[]>("/api/v1/posture/latest"),
  postureHistory: (id: string) => request<AssessmentResponse[]>(`/api/v1/endpoints/${id}/posture`),

  /** Resolves to null (HTTP 204) when the endpoint has never had a hardware check. */
  latestHardwareOrNull: (id: string) =>
    request<HardwareHealthResponse | null>(`/api/v1/endpoints/${id}/hardware-health/latest`),
  latestHardware: (id: string) =>
    request<HardwareHealthResponse | null>(`/api/v1/endpoints/${id}/hardware-health/latest`),
  latestHardwareAll: () => request<HardwareHealthResponse[]>("/api/v1/hardware-health/latest"),
  hardwareHistory: (id: string) => request<HardwareHealthResponse[]>(`/api/v1/endpoints/${id}/hardware-health`),
  latestPostureOrNull: async (id: string): Promise<AssessmentResponse | null> => {
    const history = await request<AssessmentResponse[]>(`/api/v1/endpoints/${id}/posture`);
    return history[0] ?? null; // history is newest first; empty list means never assessed
  },

  sessionHistory: (id: string) => request<SessionEvent[]>(`/api/v1/endpoints/${id}/sessions`),

  dashboardSummary: () => request<DashboardSummary>("/api/v1/dashboard/summary"),
  dashboardTrend: (days = 7) => request<TrendPoint[]>(`/api/v1/dashboard/trend?days=${days}`),
  dashboardCategories: () => request<CategoryRate[]>("/api/v1/dashboard/categories"),

  listJobs: () => request<JobResponse[]>("/api/v1/jobs"),
  listJobsForEndpoint: (id: string) => request<JobResponse[]>(`/api/v1/jobs/endpoint/${id}`),
  enqueueJob: (endpointId: string, jobType: JobType) =>
    request<JobResponse>("/api/v1/jobs", {
      method: "POST",
      body: JSON.stringify({ endpointId, jobType }),
    }),

  sharePosture: (endpointId: string) =>
    request<{ success: boolean; detail: string }>("/api/v1/ise/posture/share", {
      method: "POST",
      body: JSON.stringify({ endpointId }),
    }),
  restrict: (endpointId: string, policy?: string) =>
    request<{ success: boolean; detail: string }>("/api/v1/ise/enforcement/restrict", {
      method: "POST",
      body: JSON.stringify({ endpointId, policy }),
    }),
  clearRestriction: (endpointId: string) =>
    request<{ success: boolean; detail: string }>("/api/v1/ise/enforcement/clear", {
      method: "POST",
      body: JSON.stringify({ endpointId }),
    }),

  auditActions: (endpointId?: string) =>
    request<IseActionAudit[]>(`/api/v1/audit/ise-actions${endpointId ? `?endpointId=${endpointId}` : ""}`),
  iseActionsState: () => request<IseActionState[]>("/api/v1/ise/actions/state"),

  iseStatus: () => request<IseStatus>("/api/v1/ise/status"),
  systemHealth: () => request<SystemHealth>("/api/v1/system/health"),

  listApplications: () => request<AppRow[]>("/api/v1/applications"),
  listPorts: () => request<PortRow[]>("/api/v1/ports"),

  policy: () => request<AppPolicy>("/api/v1/policy/apps"),
  policyHistory: () => request<AppPolicy[]>("/api/v1/policy/apps/history"),
  updatePolicy: (requiredApps: string[], blockedApps: string[]) =>
    request<AppPolicy>("/api/v1/policy/apps", {
      method: "PUT",
      body: JSON.stringify({ requiredApps, blockedApps }),
    }),

  users: () => request<UserView[]>("/api/v1/users"),
  createUser: (username: string, password: string, role: UserRole) =>
    request<UserView>("/api/v1/users", { method: "POST", body: JSON.stringify({ username, password, role }) }),
  updateUser: (id: string, patch: { role?: UserRole; enabled?: boolean }) =>
    request<UserView>(`/api/v1/users/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  // Uses fetch directly: the endpoint returns 204 with no body.
  resetPassword: (id: string, password: string) =>
    fetch(`/api/v1/users/${id}/password`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${getToken()}` },
      body: JSON.stringify({ password }),
    }).then(async (r) => {
      if (r.status === 403) throw new Error("Your role does not allow this action");
      if (!r.ok) throw new Error((await r.json().catch(() => null))?.message ?? `Request failed: ${r.status}`);
    }),
  deleteUser: (id: string) =>
    fetch(`/api/v1/users/${id}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${getToken()}` },
    }).then(async (r) => {
      if (r.status === 403) throw new Error("Your role does not allow this action");
      if (!r.ok) throw new Error((await r.json().catch(() => null))?.message ?? `Request failed: ${r.status}`);
    }),

  // ── Warranty ────────────────────────────────────────────────────────────────
  listWarranty: () => request<WarrantyView[]>("/api/v1/warranty"),

  uploadWarrantyCsv: (file: File): Promise<WarrantyUploadResult> => {
    const token = getToken();
    const form = new FormData();
    form.append("file", file);
    return fetch("/api/v1/warranty/upload", {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: form,
    }).then(async (r) => {
      if (r.status === 403) throw new Error("Your role does not allow this action");
      if (!r.ok) {
        const body = await r.json().catch(() => null);
        throw new Error(body?.message ?? `Upload failed: ${r.status}`);
      }
      return r.json() as Promise<WarrantyUploadResult>;
    });
  },
};