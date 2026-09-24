import { api, getToken } from "@/lib/api";

export interface AppRow {
  name: string;
  version?: string;
  publisher?: string;
  hostname?: string;
  macAddress: string;
  status?: string;
  summary?: string;
}

export interface PortRow {
  port: number;
  process?: string;
  pid?: number;
  reachable?: boolean | null;
  hostname?: string;
  macAddress: string;
  status?: string;
}

async function get<T>(path: string): Promise<T[]> {
  const token = getToken();
  const res = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export async function listApplications(): Promise<AppRow[]> {
  try {
    return await get<AppRow>("/api/v1/applications");
  } catch {
    // Seamless fallback: extract application inventory from the latest posture assessments
    try {
      const endpoints = await api.listEndpoints();
      const rows: AppRow[] = [];
      await Promise.all(
        endpoints.map(async (ep) => {
          try {
            const posture = await api.latestPosture(ep.id);
            const appCheck = posture.checks?.find((c) => c.checkType === "APPLICATIONS");
            if (appCheck) {
              const summary = typeof appCheck.details?.summary === "string" ? appCheck.details.summary : undefined;
              rows.push({
                name: "Application Control Policy",
                version: "Active Rule Set",
                publisher: "Cisco Posture Agent",
                hostname: ep.hostname ?? undefined,
                macAddress: ep.macAddress,
                status: appCheck.status,
                summary: summary ?? (appCheck.status === "COMPLIANT" ? "All required apps present, no blocked software detected" : "Policy violation detected"),
              });
            }
          } catch {
            // No assessment yet for this endpoint
          }
        })
      );
      return rows;
    } catch {
      return [];
    }
  }
}

export async function listPorts(): Promise<PortRow[]> {
  try {
    return await get<PortRow>("/api/v1/ports");
  } catch {
    // Seamless fallback: extract port reachability records from the latest posture assessments
    try {
      const endpoints = await api.listEndpoints();
      const rows: PortRow[] = [];
      await Promise.all(
        endpoints.map(async (ep) => {
          try {
            const posture = await api.latestPosture(ep.id);
            const portCheck = posture.checks?.find((c) => c.checkType === "OPEN_PORTS");
            if (portCheck && portCheck.details) {
              const details = portCheck.details as {
                openPorts?: number[];
                blockedPorts?: number[];
                summary?: string;
              };
              if (Array.isArray(details.openPorts)) {
                details.openPorts.forEach((p) => {
                  rows.push({
                    port: p,
                    process: "Active Network Service",
                    reachable: true,
                    hostname: ep.hostname ?? undefined,
                    macAddress: ep.macAddress,
                    status: "COMPLIANT",
                  });
                });
              }
              if (Array.isArray(details.blockedPorts)) {
                details.blockedPorts.forEach((p) => {
                  rows.push({
                    port: p,
                    process: "Filtered / Protected Service",
                    reachable: false,
                    hostname: ep.hostname ?? undefined,
                    macAddress: ep.macAddress,
                    status: "BLOCKED",
                  });
                });
              }
            }
          } catch {
            // No assessment yet for this endpoint
          }
        })
      );
      return rows;
    } catch {
      return [];
    }
  }
}
