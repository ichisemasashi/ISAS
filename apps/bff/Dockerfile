FROM node:26.7.0-bookworm-slim@sha256:cd565714d4da3e84bfd341e31448f81d47c6362198f152345297c9c1154e6341

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
