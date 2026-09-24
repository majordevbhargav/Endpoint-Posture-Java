"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, getToken, clearToken } from "@/lib/api";
import { clearCurrentUser } from "@/lib/auth";
import { isTokenValid } from "@/lib/session";
import { Sidebar } from "@/components/layout/Sidebar";
import { Topbar } from "@/components/layout/Topbar";

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
    // Confirm the backend still accepts this token before showing anything.
    api
      .listEndpoints()
      .then(() => setReady(true))
      .catch(() => {
        clearToken();
        clearCurrentUser();
        router.replace("/login");
      });
  }, [router]);

  if (!ready) return null;

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar />
        <main className="min-w-0 flex-1 overflow-y-auto overflow-x-hidden p-8">{children}</main>
      </div>
    </div>
  );
}
