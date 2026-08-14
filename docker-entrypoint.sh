#!/bin/sh
set -eu

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

if [ ! -f /app/config.json ]; then
  if [ -z "${BOT_TOKEN:-}" ]; then
    echo "BOT_TOKEN is required when /app/config.json is not mounted." >&2
    exit 1
  fi

  escaped_bot_token="$(json_escape "$BOT_TOKEN")"
  escaped_mini_app_url="$(json_escape "${MINI_APP_URL:-}")"

  if [ -n "${MINI_APP_URL:-}" ]; then
    printf '{\n  "bot_token": "%s",\n  "mini_app_url": "%s"\n}\n' "$escaped_bot_token" "$escaped_mini_app_url" > /app/config.json
  else
    printf '{\n  "bot_token": "%s"\n}\n' "$escaped_bot_token" > /app/config.json
  fi
fi

exec /app/reyumebot "$@"
