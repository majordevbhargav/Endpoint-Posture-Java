/** @type {import('next').NextConfig} */
const nextConfig = {
  // Next 15+/16 blocks dev-only resources (including the HMR socket) from
  // origins other than localhost. List any other address you browse from.
  allowedDevOrigins: ["127.0.0.1"],
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: "http://localhost:8090/api/:path*",
      },
    ];
  },
};

export default nextConfig;