import type { Metadata } from "next";
import Script from "next/script";
// @ts-expect-error Next.js processes this stylesheet import at build time.
import "./globals.css";

export const metadata: Metadata = {
  title: "Endpoint Posture",
  description: "Endpoint posture and compliance visibility",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <Script id="theme-init" strategy="beforeInteractive">
          {`try{if(localStorage.getItem('theme')==='light'){document.documentElement.classList.add('light')}}catch(e){}`}
        </Script>
      </head>
      <body className="min-h-screen antialiased">{children}</body>
    </html>
  );
}