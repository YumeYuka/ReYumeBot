FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY reyumebot /app/reyumebot
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN chmod +x /app/reyumebot /app/docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]

