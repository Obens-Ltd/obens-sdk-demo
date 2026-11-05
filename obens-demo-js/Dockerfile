# The platform + its Obens integration, on http://localhost:3000 (see docker-compose.yml)
# node:24 (not -slim): the SDK is installed from its git tag, so git must be there.
FROM node:24-bookworm
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY . .
# the SQLite file lives in /data: mount a volume there to keep your changes between runs
ENV PORT=3000 DB_PATH=/data/platform.db
RUN mkdir -p /data
VOLUME /data
EXPOSE 3000
CMD ["node", "src/server.js"]
