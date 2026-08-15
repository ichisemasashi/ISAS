FROM node:22.18.0-bookworm-slim@sha256:752ea8a2f758c34002a0461bd9f1cee4f9a3c36d48494586f60ffce1fc708e0e

ENV NODE_ENV=production
WORKDIR /app
COPY apps/bff/package.json apps/bff/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY --chown=node:node apps/bff/bin ./bin
COPY --chown=node:node apps/bff/runtime-adapters ./runtime-adapters
COPY --chown=node:node apps/bff/src ./src
USER node
EXPOSE 3000
CMD ["node", "bin/server.mjs", "start"]
