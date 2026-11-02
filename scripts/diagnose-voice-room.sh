#!/usr/bin/env bash
# Diagnose a wedged Janus videoroom on a provisioned OAK server — and,
# with --destroy, kick it so it re-creates clean on the next join.
#
# Background: VoiceGhostSweeper's WARN "control join produced no joined
# event (room N)" every minute for a whole day (live report 2026-10-30)
# means the room neither lets the server's control session in nor dies.
# This script replays oak-server's own access recipe by hand so a human
# can see what Janus answers.
#
# Run ON THE SERVER (root or a docker-group user):
#   ./diagnose-voice-room.sh            # diagnose room 6 (the default)
#   ./diagnose-voice-room.sh 12         # diagnose room 12
#   ./diagnose-voice-room.sh 6 --destroy  # diagnose, then destroy the room
#
# The recipe mirrors oak-server: the plugin secret of room N is
# HMAC-SHA256(JANUS_ADMIN_SECRET, "oak-voice-secret:N") and Janus requests
# carry the token in the POST body (this build ignores ?token= on POST).
set -u

ROOM="${1:-6}"
WANT_DESTROY="${2:-}"

OAK_DIR="${OAK_DIR:-/opt/oak}"
cd "$OAK_DIR" || { echo "no $OAK_DIR — is this a provisioned server?"; exit 1; }

ADMIN=$(grep '^JANUS_ADMIN_SECRET=' .env | cut -d= -f2-)
[ -n "$ADMIN" ] || { echo "JANUS_ADMIN_SECRET not found in $OAK_DIR/.env"; exit 1; }
SECRET=$(printf 'oak-voice-secret:%s' "$ROOM" | openssl dgst -sha256 -hmac "$ADMIN" -hex | awk '{print $NF}')

# The oak container: strip the known periphery first (its names ALSO contain
# "oak" — oak-janus, oak-postgres…), then take what's left; OAK_CONTAINER
# overrides the guess when the deployment names it differently.
OAK="${OAK_CONTAINER:-$(docker ps --format '{{.Names}}' \
  | grep -viE -- '-(janus|postgres|minio|coturn|caddy)(-[0-9]+)?$' \
  | grep -m1 -i 'oak' || true)}"
JANUS=$(docker ps --format '{{.Names}}' | grep -m1 janus)
if [ -z "$OAK" ] || [ -z "$JANUS" ]; then
  echo "containers not found (oak='$OAK' janus='$JANUS')"
  echo "container names on this machine:"
  docker ps --format '  {{.Names}}'
  echo "hint: OAK_CONTAINER=<name> sh $0 $ROOM"
  exit 1
fi

# Prefer running curl from the oak container (it sits on the compose network);
# fall back to the janus container itself if oak's image has no curl.
EXEC_IN="$OAK"
if ! docker exec "$OAK" sh -c 'command -v curl >/dev/null' 2>/dev/null; then
  if docker exec "$JANUS" sh -c 'command -v curl >/dev/null' 2>/dev/null; then
    EXEC_IN="$JANUS"
  else
    echo "no curl inside either container — install curl or rerun from a container that has it"
    exit 1
  fi
fi
q() { docker exec "$EXEC_IN" curl -s -m 5 "$@"; echo; }

echo "== containers: executor=$EXEC_IN janus=$JANUS room=$ROOM =="

echo; echo '=== 1. janus own log (the ground truth) ==='
docker logs "$JANUS" --tail 120 2>&1 | grep -iE 'warn|err|videoroom' | tail -40

echo; echo '=== 2. admin API: tokens & live sessions ==='
TOKENS_JSON=$(docker exec "$EXEC_IN" curl -s -m 5 http://janus:7088/admin \
  -H 'Content-Type: application/json' \
  -d "{\"janus\":\"list_tokens\",\"transaction\":\"d\",\"admin_secret\":\"$ADMIN\"}")
echo "$TOKENS_JSON"
TOKEN=$(printf '%s' "$TOKENS_JSON" | grep -o '"token": *"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
if [ -z "$TOKEN" ]; then
  echo 'no tokens registered — Janus was likely restarted (the room should be gone; see step 3)'
fi
q "{\"janus\":\"list_sessions\",\"transaction\":\"d\",\"admin_secret\":\"$ADMIN\"}"

echo; echo '=== 3. plugin API: rooms and room participants ==='
SID=""; HID=""
if [ -n "$TOKEN" ]; then
  SID=$(docker exec "$EXEC_IN" curl -s -m 5 http://janus:8088/janus \
    -H 'Content-Type: application/json' \
    -d "{\"janus\":\"create\",\"transaction\":\"d\",\"token\":\"$TOKEN\"}" \
    | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  if [ -n "$SID" ]; then
    HID=$(docker exec "$EXEC_IN" curl -s -m 5 "http://janus:8088/janus/$SID" \
      -H 'Content-Type: application/json' \
      -d "{\"janus\":\"attach\",\"plugin\":\"janus.plugin.videoroom\",\"transaction\":\"d\",\"token\":\"$TOKEN\"}" \
      | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  fi
fi
echo "session='$SID' handle='$HID'"
if [ -z "$SID" ] || [ -z "$HID" ]; then
  echo 'could not open a plugin handle — attach/token step failed (see the raw answers above)'
  exit 1
fi
echo '--- all rooms:'
docker exec "$EXEC_IN" curl -s -m 5 "http://janus:8088/janus/$SID/$HID" \
  -H 'Content-Type: application/json' \
  -d "{\"janus\":\"message\",\"transaction\":\"d\",\"token\":\"$TOKEN\",\"body\":{\"request\":\"list\",\"secret\":\"$SECRET\"}}"; echo
echo "--- participants of room $ROOM:"
docker exec "$EXEC_IN" curl -s -m 5 "http://janus:8088/janus/$SID/$HID" \
  -H 'Content-Type: application/json' \
  -d "{\"janus\":\"message\",\"transaction\":\"d\",\"token\":\"$TOKEN\",\"body\":{\"request\":\"listparticipants\",\"room\":$ROOM,\"secret\":\"$SECRET\"}}"; echo

if [ "$WANT_DESTROY" = "--destroy" ]; then
  echo; echo "=== 4. DESTROY room $ROOM (living participants would be kicked) ==="
  printf 'type DESTROY to confirm: '
  read -r CONFIRM
  if [ "$CONFIRM" = "DESTROY" ]; then
    docker exec "$EXEC_IN" curl -s -m 5 "http://janus:8088/janus/$SID/$HID" \
      -H 'Content-Type: application/json' \
      -d "{\"janus\":\"message\",\"transaction\":\"d\",\"token\":\"$TOKEN\",\"body\":{\"request\":\"destroy\",\"room\":$ROOM,\"secret\":\"$SECRET\"}}"; echo
    echo 'the room re-creates clean when someone next joins the channel'
  else
    echo 'destroy cancelled'
  fi
fi

# Close the diagnostic session (best effort — session_timeout would reap it anyway).
[ -n "$SID" ] && docker exec "$EXEC_IN" curl -s -m 5 "http://janus:8088/janus/$SID" \
  -H 'Content-Type: application/json' \
  -d "{\"janus\":\"destroy\",\"transaction\":\"d\",\"token\":\"$TOKEN\"}" >/dev/null
echo; echo 'done.'
