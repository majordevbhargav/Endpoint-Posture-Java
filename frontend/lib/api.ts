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
  cpuScore: number;
  memoryScore: number;
  storageScore: number;
  batteryScore: number | null;
  overallScore: number;
  overallBand: HardwareBand;
  hardwareEventCount: number | null;
  warrantyStatus: string | null;
  warrantyDaysRemaining: number | null;
  collectedAt: string;
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

export interface AppRow {
  name: string;
  version?: string;
  publisher?: string;
  hostname?: string;
  macAddress: string;
}

export interface PortRow {
  port: number;
  process?: string;
  pid?: number;
  reachable?: boolean | null;
  hostname?: string;
  macAddress: string;
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
    clearToken();
    if (typeof window !== "undefined") window.location.href = "/login";
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.error ?? body?.message ?? `Request failed: ${res.status}`);
  }

  if (res.status === 401) { clearToken(); window.location.href = "/login"; throw new Error("Session expired"); }

  if (res.status === 204) return undefined as T;
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
  postureHistory: (id: string) => request<AssessmentResponse[]>(`/api/v1/endpoints/${id}/posture`),

  latestHardware: (id: string) => request<HardwareHealthResponse>(`/api/v1/endpoints/${id}/hardware-health/latest`),
  hardwareHistory: (id: string) => request<HardwareHealthResponse[]>(`/api/v1/endpoints/${id}/hardware-health`),

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

  listApplications: () => request<AppRow[]>("/api/v1/applications"),
  listPorts: () => request<PortRow[]>("/api/v1/ports"),
};