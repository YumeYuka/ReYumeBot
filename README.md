# ReYumeBot

A lightweight, native Telegram bot for group member verification, moderation, and Bilibili video delivery built with Kotlin/Native.

## Docker Compose

### 1. Configuration (`.env`)

```env
BOT_TOKEN=your_bot_token
MINI_APP_URL=https://verify.example.com
BILIBILI_ADMIN_ID=123456789

# Optional: enable the built-in local telegram-bot-api server (upload limit 50MB → 2GB).
# Get these from https://my.telegram.org/apps (any Telegram account can create one app).
TELEGRAM_API_ID=12345678
TELEGRAM_API_HASH=0123456789abcdef0123456789abcdef
# Optional overrides (defaults shown):
# TELEGRAM_API_PORT=8081
# TELEGRAM_API_BASE_URL=http://127.0.0.1:8081
```

`BILIBILI_ADMIN_ID` is the Telegram numeric user ID allowed to use `/bili_login`. Keep this value private and do not configure it as a group ID.

When `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` are both set, the container entrypoint starts a local
[telegram-bot-api](https://github.com/tdlib/telegram-bot-api) server (prebuilt binary from
`aiogram/telegram-bot-api`, no tdlib compilation needed) and points the bot at it. In this local mode
media files are sent by absolute file path — the API server streams them from disk itself, so memory
usage stays flat even for large files, and the upload limit rises from 50MB to 2GB. When they are
absent, the bot talks to `api.telegram.org` directly exactly as before.

### 2. Run

```bash
docker compose up -d
```

The Compose configuration persists Bilibili login credentials and temporary downloads under `./data`. Do not share or commit `data/bilibili-credentials.json` because it contains login cookies.

## Bilibili Video Delivery

- Send a normal Bilibili video URL, `b23.tv` short URL, `BV` ID, or `av` ID in a chat; the bot automatically resolves and sends the video.
- The bot requests the highest quality currently accessible to its Bilibili login state.
- Videos longer than 10 minutes are rejected before downloading.
- `/bili_login` sends a QR code to the configured administrator. Scan it in the Bilibili mobile app to update the bot's login cookies.
- DASH video and audio streams are combined with `ffmpeg`; the Docker image installs both `curl` and `ffmpeg`.

Restricted, paid, member-only, and region-restricted videos are only handled when the configured account may legitimately access their streams. The bot does not bypass Bilibili access controls.

## NetEase Cloud Music Delivery

- Send a NetEase song URL (`music.163.com/song?id=...`, mobile/hash variants) or a `163cn.tv` / `163cn.link` short link in a chat; the bot automatically resolves and sends the audio.
- Requests are signed with the EAPI scheme (pure-Kotlin AES-128-ECB + MD5, no native crypto dependency) and use an anonymous visitor token — no login required.
- Quality is requested at the highest level that fits Telegram's 50 MB upload limit, falling back `hires → exhigh → standard` as needed. VIP-only / removed tracks report a clear error.
- The audio message carries title/performer metadata and an HTML caption with song, album, size/bitrate info and a `via @<bot>` attribution. Only single tracks are supported (no playlists/albums).

## Build from Source

Built with Amper.

```bash
# Linux / macOS
chmod +x ./kotlin
./kotlin build -v release
./kotlin run
```

The bundled local `telegram-bot-api` server is a Linux container feature only. Linux Docker
deployments can enable it with `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`; native macOS builds
use the official Telegram endpoint.

## Environment Variables

- `BOT_TOKEN`: Telegram bot token from @BotFather. Required.
- `MINI_APP_URL`: Verification WebApp frontend URL. Optional.
- `BILIBILI_ADMIN_ID`: Telegram numeric user ID permitted to run `/bili_login`. Optional; Bilibili login remains disabled when absent.
