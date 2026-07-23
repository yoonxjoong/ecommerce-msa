#!/bin/bash
# 정상 주문 흐름: 재고 확인 -> 결제 승인 -> CONFIRMED.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "2. 정상 주문 -> CONFIRMED"

RESP=$(curl -s -X POST "${GW}/orders" -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}')
echo "$RESP"
STATUS=$(echo "$RESP" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
check "정상 주문 CONFIRMED" "CONFIRMED" "$STATUS" || FAIL=1

exit $FAIL
