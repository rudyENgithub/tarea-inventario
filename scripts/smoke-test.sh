#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:8080}"
MAILPIT_URL="${2:-http://localhost:8025}"

wait_health() {
  local deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    if curl -fsS "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  echo "Health did not become UP at $BASE_URL" >&2
  return 1
}

wait_health
curl -fsS -X DELETE "$MAILPIT_URL/api/v1/messages" >/dev/null

SKU="SMOKE-$(date +%s%3N)"
O1="O1-$SKU"
O2="O2-$SKU"
F1="F1-$SKU"
F2="F2-$SKU"
O9="O9-$SKU"
curl -fsS -X PUT "$BASE_URL/api/v1/products/$SKU" -H 'Content-Type: application/json' -d '{"category":"STANDARD"}'
curl -fsS -X POST "$BASE_URL/api/v1/products/$SKU/stock" -H 'Content-Type: application/json' -d '{"quantity":10}' >/dev/null

BODY='{"orderId":"'"$O1"'","sku":"'"$SKU"'","quantity":5}'
R1=$(curl -sS -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/reservations" -H 'Content-Type: application/json' -d "$BODY")
CODE1=${R1##*$'\n'}; PAYLOAD1=${R1%$'\n'*}
[[ "$CODE1" == "200" ]] || { echo "reserve failed $CODE1"; exit 1; }
R2=$(curl -sS -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/reservations" -H 'Content-Type: application/json' -d "$BODY")
CODE2=${R2##*$'\n'}; PAYLOAD2=${R2%$'\n'*}
[[ "$CODE2" == "200" && "$PAYLOAD1" == "$PAYLOAD2" ]] || { echo "replay mismatch"; exit 1; }

AVAIL=$(curl -fsS "$BASE_URL/api/v1/products/$SKU/availability" | sed -n 's/.*"available":\([0-9]*\).*/\1/p')
[[ "$AVAIL" == "5" ]] || { echo "expected available 5"; exit 1; }

deadline=$((SECONDS + 15))
while (( SECONDS < deadline )); do
  TOTAL=$(curl -fsS "$MAILPIT_URL/api/v1/messages" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')
  if [[ "$TOTAL" == "1" ]]; then break; fi
  sleep 1
done
[[ "$TOTAL" == "1" ]] || { echo "expected 1 mail, got $TOTAL"; exit 1; }

curl -fsS -X POST "$BASE_URL/api/v1/reservations" -H 'Content-Type: application/json' \
  -d '{"orderId":"'"$O2"'","sku":"'"$SKU"'","quantity":1}' >/dev/null
AVAIL=$(curl -fsS "$BASE_URL/api/v1/products/$SKU/availability" | sed -n 's/.*"available":\([0-9]*\).*/\1/p')
[[ "$AVAIL" == "4" ]] || { echo "expected available 4"; exit 1; }
TOTAL=$(curl -fsS "$MAILPIT_URL/api/v1/messages" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')
[[ "$TOTAL" == "1" ]] || { echo "RF-A2 expected still 1 mail"; exit 1; }

C1=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/reservations/$O1/confirm")
C2=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/reservations/$O1/confirm")
[[ "$C1" == "204" && "$C2" == "204" ]] || { echo "confirm failed"; exit 1; }

FLASH="FLASH-$(date +%s%3N)"
curl -fsS -X PUT "$BASE_URL/api/v1/products/$FLASH" -H 'Content-Type: application/json' -d '{"category":"FLASH_SALE"}'
curl -fsS -X POST "$BASE_URL/api/v1/products/$FLASH/stock" -H 'Content-Type: application/json' -d '{"quantity":10}' >/dev/null
OVER=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/reservations" -H 'Content-Type: application/json' \
  -d '{"orderId":"'"$F1"'","sku":"'"$FLASH"'","quantity":3}')
[[ "$OVER" == "422" ]] || { echo "expected 422 got $OVER"; exit 1; }
OKF=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/reservations" -H 'Content-Type: application/json' \
  -d '{"orderId":"'"$F2"'","sku":"'"$FLASH"'","quantity":2}')
[[ "$OKF" == "200" ]] || { echo "flash 2 should succeed"; exit 1; }

SHORT=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/reservations" -H 'Content-Type: application/json' \
  -d '{"orderId":"'"$O9"'","sku":"'"$SKU"'","quantity":99}')
[[ "$SHORT" == "409" ]] || { echo "expected 409 got $SHORT"; exit 1; }

echo "SMOKE TEST OK"
