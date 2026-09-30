import type { Metadata } from "next";
import { cookies } from "next/headers";

import "./globals.css";

export const metadata: Metadata = {
  title: "Endpoint Posture",
  description: "Endpoint posture and compliance visibility",
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  // The theme lives in a cookie so the server can render the right class
  // immediately: no inline script, no flash of the wrong theme.
  const theme = (await cookies()).get("theme")?.value;

  return (
    <html lang="en" className={theme === "light" ? "light" : undefined} suppressHydrationWarning>
      <body className="min-h-screen antialiased">{children}</body>
    </html>
  );
}