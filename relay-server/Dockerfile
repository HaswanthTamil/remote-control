# syntax=docker/dockerfile:1
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV KEYS_FILE=/data/devices.json

# Runtime credentials (PAIR_TOKEN, PORT, heartbeat tuning) are baked into the
# image from relay-server/.env. Override at runtime with
# `docker run -e PAIR_TOKEN=...` if you do not want the baked value used.
COPY .env ./
COPY index.js ./
COPY --from=deps /app/node_modules ./node_modules

# Persistent device keystore (registered public keys survive restarts).
# A fresh named volume inherits this directory's ownership (node user).
RUN mkdir -p /data && chown -R node:node /data
VOLUME /data

USER node
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:3000/').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "index.js"]