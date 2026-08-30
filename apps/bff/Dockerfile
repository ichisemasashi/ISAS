FROM node:26.8.1-bookworm-slim@sha256:367679cf9792759492a486e4aa4b421764d71a9546a6dae8aab81a99eb797b3e

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
