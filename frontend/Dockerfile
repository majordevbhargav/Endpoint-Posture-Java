# ── Stage 1: build ─────────────────────────────────────────────────────────────
FROM node:22-alpine AS builder

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Next.js bakes rewrites() into the build, so the backend address is a BUILD
# argument, not a runtime variable. On Windows Docker Desktop the host is
# host.docker.internal. Rebuild the image to point it somewhere else:
#   docker build --build-arg BACKEND_URL=http://myhost:8090 -t ep-frontend .
ARG BACKEND_URL=http://host.docker.internal:8090
ENV BACKEND_URL=$BACKEND_URL
RUN npm run build

# ── Stage 2: runtime ───────────────────────────────────────────────────────────
FROM node:22-alpine AS runner

RUN addgroup --system nextjs && adduser --system --ingroup nextjs nextjs

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

# Standalone output: self-contained server.js plus a minimal node_modules.
# Static assets are copied separately (the standalone server does not add them).
COPY --from=builder --chown=nextjs:nextjs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nextjs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nextjs /app/public ./public

USER nextjs

EXPOSE 3000

ENV PORT=3000
ENV HOSTNAME=0.0.0.0

CMD ["node", "server.js"]