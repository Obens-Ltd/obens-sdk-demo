# The platform + its Obens integration, on http://localhost:3000 (see docker-compose.yml)
# python:3.13 (not -slim): the SDK is installed from its git tag, so git must be there.
FROM python:3.13-bookworm
WORKDIR /app
ENV PYTHONUNBUFFERED=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
# the SQLite file lives in /data: mount a volume there to keep your changes between runs
ENV PORT=3000 DB_PATH=/data/platform.db
RUN mkdir -p /data
VOLUME /data
EXPOSE 3000
CMD ["python", "-m", "app.server"]
