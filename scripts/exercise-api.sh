#!/usr/bin/env bash
# Drive real traffic through the reservation core so that verify-invariants.sh has something to
# check. A clean database satisfies every invariant trivially, which would make the CI job a test
# of nothing.
#
# Deliberately exercises the awkward paths as well as the happy one: a duplicate idempotency key,
# a declined payment, a gateway timeout, an abandoned hold, and a cancel.
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:18081}"
BUYER_HEADER="X-Willcall-User"

# Idempotency keys are unique per invocation. A fixed key would be reused across runs with a
# different hold id in the body, and the service would correctly answer 422 — which is the right
# behaviour and the wrong test.
RUN_ID="$(date -u +%s)-$$"

fail() { printf 'exercise-api: %s\n' "$1" >&2; exit 1; }

api() {
  local method="$1" path="$2" body="${3:-}" buyer="${4:-buyer-exercise-1-$RUN_ID}" key="${5:-}"
  local args=(-sS -X "$method" -H "Content-Type: application/json" -H "$BUYER_HEADER: $buyer")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}" "$BASE_URL$path"
}

jqv() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }

printf 'exercising %s\n' "$BASE_URL"
curl -fsS "$BASE_URL/api/system/status" >/dev/null || fail "the API is not answering"

EVENT_JSON=$(api POST /api/events '{
  "venueName": "CI Arena",
  "eventName": "CI Exercise",
  "holdTtlSeconds": 30,
  "maxSeatsPerOrder": 6,
  "status": "ON_SALE",
  "priceTiers": [{"name": "Standard", "amountCents": 4500, "currency": "USD"}],
  "sections": [{"name": "Floor", "rowCount": 5, "seatsPerRow": 10, "priceTierName": "Standard"}]
}')
EVENT_ID=$(printf '%s' "$EVENT_JSON" | jqv "['id']") || fail "could not create an event: $EVENT_JSON"
printf 'created event %s\n' "$EVENT_ID"

# 1. Happy path: hold, confirm.
HOLD=$(api POST "/api/events/$EVENT_ID/holds" '{"quantity": 2}' buyer-exercise-1-$RUN_ID "key-hold-1-$RUN_ID")
HOLD_ID=$(printf '%s' "$HOLD" | jqv "['holdId']") || fail "hold failed: $HOLD"
ORDER=$(api POST /api/orders "{\"holdId\": \"$HOLD_ID\"}" buyer-exercise-1-$RUN_ID "key-order-1-$RUN_ID")
printf '%s' "$ORDER" | grep -q CONFIRMED || fail "confirm failed: $ORDER"
printf 'confirmed an order\n'

# 2. The same idempotency key again: must not produce a second order.
REPLAY=$(api POST /api/orders "{\"holdId\": \"$HOLD_ID\"}" buyer-exercise-1-$RUN_ID "key-order-1-$RUN_ID")
printf '%s' "$REPLAY" | grep -q CONFIRMED || fail "replay did not return the original order: $REPLAY"
printf 'idempotent replay returned the original order\n'

# 3. Declined payment: the seats must go back.
HOLD2=$(api POST "/api/events/$EVENT_ID/holds" '{"quantity": 2}' buyer-exercise-2-$RUN_ID "key-hold-2-$RUN_ID")
HOLD2_ID=$(printf '%s' "$HOLD2" | jqv "['holdId']")
api POST /api/orders "{\"holdId\": \"$HOLD2_ID\", \"paymentBehavior\": \"DECLINE\"}" buyer-exercise-2-$RUN_ID "key-order-2-$RUN_ID" >/dev/null || true
printf 'exercised a declined payment\n'

# 4. Gateway timeout: the seats must stay held.
HOLD3=$(api POST "/api/events/$EVENT_ID/holds" '{"quantity": 1}' buyer-exercise-3-$RUN_ID "key-hold-3-$RUN_ID")
HOLD3_ID=$(printf '%s' "$HOLD3" | jqv "['holdId']")
api POST /api/orders "{\"holdId\": \"$HOLD3_ID\", \"paymentBehavior\": \"TIMEOUT\"}" buyer-exercise-3-$RUN_ID "key-order-3-$RUN_ID" >/dev/null || true
printf 'exercised a gateway timeout\n'

# 5. Explicit cancel.
HOLD4=$(api POST "/api/events/$EVENT_ID/holds" '{"quantity": 3, "together": true}' buyer-exercise-4-$RUN_ID "key-hold-4-$RUN_ID")
HOLD4_ID=$(printf '%s' "$HOLD4" | jqv "['holdId']")
api DELETE "/api/holds/$HOLD4_ID" '' buyer-exercise-4-$RUN_ID >/dev/null
printf 'exercised a cancel\n'

# 6. Abandoned hold, left to expire.
api POST "/api/events/$EVENT_ID/holds" '{"quantity": 2}' buyer-exercise-5-$RUN_ID "key-hold-5-$RUN_ID" >/dev/null
printf 'left a hold to expire\n'

# 7. A losing request within the schema's bounds but over this event's per-order cap: 409,
#    because the request is well-formed and the answer is "no", not "you typed it wrong".
STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  -H "Content-Type: application/json" -H "$BUYER_HEADER: buyer-exercise-6-$RUN_ID" \
  -d '{"quantity": 7}' "$BASE_URL/api/events/$EVENT_ID/holds")
[ "$STATUS" = "409" ] || fail "expected 409 for a request over this event's per-order cap, got $STATUS"
printf "over-cap request correctly refused with 409\n"

# 8. A malformed request: 400, because it is outside the schema entirely.
STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  -H "Content-Type: application/json" -H "$BUYER_HEADER: buyer-exercise-6-$RUN_ID" \
  -d '{"quantity": 9999}' "$BASE_URL/api/events/$EVENT_ID/holds")
[ "$STATUS" = "400" ] || fail "expected 400 for an out-of-schema quantity, got $STATUS"
printf "out-of-schema request correctly refused with 400\n"

# 9. Missing buyer identity: 400 rather than an anonymous hold.
STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  -H "Content-Type: application/json" -d '{"quantity": 1}' "$BASE_URL/api/events/$EVENT_ID/holds")
[ "$STATUS" = "400" ] || fail "expected 400 for a request with no buyer identity, got $STATUS"
printf "request without a buyer identity correctly refused with 400\n"

printf '\nstate after exercising:\n'
api GET "/api/events/$EVENT_ID" '' buyer-exercise-1-$RUN_ID | python3 -m json.tool | head -20
