# The platform + its Obens integration, on http://localhost:3000 (see docker-compose.yml)
FROM composer:2 AS vendor
WORKDIR /app
COPY composer.json ./
RUN composer install --no-dev --prefer-dist --no-progress --optimize-autoloader

FROM php:8.3-cli
WORKDIR /app
COPY --from=vendor /app/vendor vendor
COPY . .
# the SQLite file lives in /data: mount a volume there to keep your changes between runs
ENV PORT=3000 DB_PATH=/data/platform.db
RUN mkdir -p /data
VOLUME /data
EXPOSE 3000
# PHP's built-in server cannot run a task beside the requests: the initial load runs as its own process
CMD ["sh", "-c", "php bin/sync.php & exec php -S 0.0.0.0:3000 public/index.php"]
