#!/usr/bin/env bash
# Deregister a CI test server from the live dashboard.
#
# Every job that registers a server against the real dashboard used to leave the
# registration, its snapshot blob and any sessions behind, because nothing ever
# removed them. That is how the servers table filled up with hundreds of dead
# entries, each carrying a multi-megabyte snapshot on a host with a 1 GB quota.
#
# Jobs call this with the id and key they generated, and it never fails the job:
# a stale row is recoverable by hand, but a failing cleanup step would mask the
# real test result.
#
# Usage: cleanup-registration.sh <server-id> <api-key>

set -uo pipefail

SID="${1:-}"
KEY="${2:-}"
BASE="${AURELIUM_DASHBOARD_URL:-https://aurelium.alwaysdata.net}"

if [ -z "$SID" ]; then
  echo "No server id given, nothing to clean up"
  exit 0
fi

if [ -z "$KEY" ]; then
  echo "::warning::no api key for $SID, cannot authenticate the delete"
  exit 0
fi

echo "Deregistering $SID from $BASE"
RESP=$(curl -sS -w '\n%{http_code}' --max-time 30 \
  -X DELETE \
  -H "X-Api-Key: $KEY" \
  "$BASE/api/$SID" 2>/dev/null) || RESP=""

CODE=$(printf '%s' "$RESP" | tail -n1)
BODY=$(printf '%s' "$RESP" | head -n-1)

case "$CODE" in
  200)
    echo "  removed: $BODY"
    ;;
  404)
    echo "  already absent, nothing to do"
    ;;
  401|403)
    # Either the key is wrong or the server is already gone: the API answers
    # identically for both so it does not disclose which servers exist. Treated
    # as success, because the outcome we want, "not registered", already holds.
    echo "  already absent or key no longer valid, nothing to do"
    ;;
  000|"")
    echo "  ::warning::could not reach the dashboard to delete $SID"
    ;;
  *)
    echo "  ::warning::delete refused for $SID (HTTP $CODE) ${BODY}"
    ;;
esac

exit 0