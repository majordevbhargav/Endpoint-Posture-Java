import { api, AppRow, PortRow } from "@/lib/api";

export type { AppRow, PortRow };

// No fallback on purpose: if the backend fails, the pages show their error
// banner instead of rows invented from posture checks.
export const listApplications = (): Promise<AppRow[]> => api.listApplications();
export const listPorts = (): Promise<PortRow[]> => api.listPorts();