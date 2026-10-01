/** @type {import('next').NextConfig} */
const nextConfig = {
  // Produce a self-contained build in .next/standalone that can be run with
  // `node server.js` — required for the Docker image (C3 Stage A).
  output: "standalone",
  // Next 15+/16 blocks dev-only resources (including the HMR socket) from
  // origins other than localhost. List any other address you browse from.
  allowedDevOrigins: ["127.0.0.1"],
  async rewrites() {
    // BACKEND_URL is set in the Docker container; falls back to localhost for dev.
    const backend = process.env.BACKEND_URL ?? "http://localhost:8090";
    return [
      {
        source: "/api/:path*",
        destination: `${backend}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;