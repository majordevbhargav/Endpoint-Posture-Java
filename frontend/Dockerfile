# ── Stage 1: build ─────────────────────────────────────────────────────────────
FROM node:22-alpine AS builder

WORKDIR /app

# Copy dependency manifests first so Docker layer-caching skips `npm ci`
# on code-only changes.
COPY package.json package-lock.json ./
RUN npm ci

# Copy source and build.  NEXT_PUBLIC_* env vars that must be baked into the
# client bundle go here; runtime-only vars (BACKEND_URL) are passed at start.
COPY . .
RUN npm run build

# ── Stage 2: runtime ────────────────────────────────────────────────────────────
FROM node:22-alpine AS runner

# Run as a non-root user (uid 1001 is the conventional Next.js user).
RUN addgroup --system nextjs && adduser --system --ingroup nextjs nextjs

WORKDIR /app

ENV NODE_ENV=production
# Next.js telemetry: opt out in CI/prod; remove this line to opt in.
ENV NEXT_TELEMETRY_DISABLED=1

# The standalone build produces a self-contained server.js plus a minimal
# node_modules copy.  Static assets live in .next/static and must be copied
# separately (the standalone server does NOT serve them itself — use a CDN or
# an nginx sidecar in production; for a single-host lab the COPY below is fine).
COPY --from=builder --chown=nextjs:nextjs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nextjs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nextjs /app/public ./public

USER nextjs

EXPOSE 3000

# BACKEND_URL is read at runtime by the rewrites() in next.config.mjs when
# running in the container.  Override it to point at the backend host.
# Example: docker run -e BACKEND_URL=http://backend:8090 ...
ENV BACKEND_URL=http://localhost:8090

# PORT and HOSTNAME are respected by the Next.js standalone server.
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

CMD ["node", "server.js"]
