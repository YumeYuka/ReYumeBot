# ReYumeBot

A lightweight, native Telegram bot for group member verification and moderation built with Kotlin/Native.


## Docker Compose

### 1. Configuration (`.env`)

```env
BOT_TOKEN=your_bot_token
MINI_APP_URL=https://verify.example.com
```

### 2. Service Definition (`docker-compose.yml`)

```yaml
services:
  reyumebot:
    image: ghcr.io/yumeyuka/reyumebot:latest
    container_name: reyumebot
    restart: unless-stopped
    working_dir: /app
    environment:
      BOT_TOKEN: ${BOT_TOKEN:-}
      MINI_APP_URL: ${MINI_APP_URL:-}
```

### 3. Run

```bash
docker compose up -d
```

## Build from Source

Built with Amper.

```bash
# Windows
.\kotlin.bat build -v release
.\kotlin.bat run

# Linux / macOS
chmod +x ./kotlin
./kotlin build -v release
./kotlin run
```

## Environment Variables

- `BOT_TOKEN`: Telegram bot token from @BotFather (Required)
- `MINI_APP_URL`: Verification WebApp frontend URL (Optional)
