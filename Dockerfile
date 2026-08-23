# telegram-bot-api 使用官方预编译镜像（Alpine/musl 构建），无需从源码编译 tdlib
FROM aiogram/telegram-bot-api:9.6 AS telegram-bot-api

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    ffmpeg \
    libcurl4 \
    musl \
    && rm -rf /var/lib/apt/lists/*

# telegram-bot-api 二进制是 musl 链接的：连同 musl 版运行库一并拷入，
# 用包装脚本把 LD_LIBRARY_PATH 限定在该进程内，避免影响主程序（glibc）。
COPY --from=telegram-bot-api /usr/local/bin/telegram-bot-api /usr/local/lib/telegram-bot-api/telegram-bot-api
COPY --from=telegram-bot-api /usr/lib/libstdc++.so.6 /usr/lib/libgcc_s.so.1 /usr/lib/libssl.so.3 /usr/lib/libcrypto.so.3 /lib/libz.so.1 /usr/local/lib/telegram-bot-api/
RUN printf '#!/bin/sh\nexport LD_LIBRARY_PATH=/usr/local/lib/telegram-bot-api${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\nexec /usr/local/lib/telegram-bot-api/telegram-bot-api "$@"\n' > /usr/local/bin/telegram-bot-api \
    && chmod +x /usr/local/bin/telegram-bot-api

WORKDIR /app

COPY reyumebot /app/reyumebot
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN chmod +x /app/reyumebot /app/docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
