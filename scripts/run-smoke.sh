#!/usr/bin/env bash
# Start an isolated Fabric server, exercise the socket protocol, and shut it down.
# Used by both `make smoke` and .github/workflows/smoke-test.yml.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUN_DIR="${SMOKE_RUN_DIR:-smoke-run}"
LOG="${SMOKE_LOG:-smoke-server.log}"
RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD="${RCON_PASSWORD:-smoke}"

mkdir -p "$RUN_DIR/config" "$RUN_DIR/mcchatapi-sockets"
printf 'eula=true\n' > "$RUN_DIR/eula.txt"
{
  echo 'online-mode=false'
  echo 'server-port=25565'
  echo 'motd=MC Chat API smoke test'
  echo 'enable-rcon=true'
  echo 'broadcast-console-to-ops=false'
  printf 'rcon.password=%s\n' "$RCON_PASSWORD"
  printf 'rcon.port=%s\n' "$RCON_PORT"
} > "$RUN_DIR/server.properties"
# An empty group avoids getent/setfacl; owner-only access is enough for the test.
cat > "$RUN_DIR/config/mcchatapi.conf" <<'CONFIG'
{
  "socket-path": "mcchatapi-sockets/mcchat.sock",
  "socket-access-group": ""
}
CONFIG
rm -f "$RUN_DIR/mcchatapi-sockets/mcchat.sock"
: > "$LOG"

./gradlew runServer --console=plain -PmcChatApiRunDir="$RUN_DIR" > "$LOG" 2>&1 &
SERVER_PID=$!

cleanup() {
  python3 scripts/smoke-test.py --stop-only \
    --rcon-port "$RCON_PORT" --rcon-password "$RCON_PASSWORD" || true
  for _ in $(seq 1 20); do
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 1
  done
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

SOCKET_PATH=""
for _ in $(seq 1 180); do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "Server exited before the socket was ready" >&2
    tail -100 "$LOG" >&2
    exit 1
  fi
  SOCKET_PATH="$(sed -n 's/.*Listening on \([^ ]*\).*/\1/p' "$LOG" | head -n 1)"
  [ -n "$SOCKET_PATH" ] && break
  sleep 1
done
if [ -z "$SOCKET_PATH" ]; then
  echo "Timed out waiting for the mod to open its socket" >&2
  tail -100 "$LOG" >&2
  exit 1
fi
echo "Socket ready at $SOCKET_PATH"

python3 scripts/smoke-test.py \
  --socket "$SOCKET_PATH" \
  --log "$LOG" \
  --rcon-port "$RCON_PORT" \
  --rcon-password "$RCON_PASSWORD"
