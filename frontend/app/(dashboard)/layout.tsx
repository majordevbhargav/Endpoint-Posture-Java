"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, getToken, clearToken } from "@/lib/api";
import { clearCurrentUser } from "@/lib/auth";
import { isTokenValid } from "@/lib/session";
import { IseStatusProvider } from "@/lib/IseStatusContext";
import { Sidebar } from "@/components/layout/Sidebar";
import { Topbar } from "@/components/layout/Topbar";
import { IseBanner } from "@/components/ui/IseBanner";
import { TabStatus } from "@/components/layout/TabStatus";

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const token = getToken();
    if (!isTokenValid(token)) {
      clearToken();
      clearCurrentUser();
      router.replace("/login");
      return;
    }
    api
      .iseStatus()
      .then(() => setReady(true))
      .catch(() => {
        clearToken();
        clearCurrentUser();
        router.replace("/login");
      });
  }, [router]);

  if (!ready) {
    return (
      <div className="flex h-screen items-center justify-center text-xs text-muted">
        Connecting to backend…
      </div>
    );
  }

  return (
    <IseStatusProvider>
      <TabStatus />
      <div className="flex h-screen overflow-hidden">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <Topbar />
          <IseBanner />
          <main className="min-w-0 flex-1 overflow-y-auto overflow-x-hidden p-8">{children}</main>
        </div>
      </div>
    </IseStatusProvider>
  );
}