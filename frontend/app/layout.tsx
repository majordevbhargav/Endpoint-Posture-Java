import type { Metadata } from "next";


import "./globals.css";

export const metadata: Metadata = {
  title: "Endpoint Posture",
  description: "Endpoint posture and compliance visibility",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `
      try {
        if (localStorage.getItem('theme') === 'light') {
          document.documentElement.classList.add('light');
        }
      } catch (e) {}
    `,
          }}
        />

      </head>
      <body className="min-h-screen antialiased">{children}</body>
    </html>
  );
}
