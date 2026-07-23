#!/bin/bash
# 결제 실패를 강제로 일으켜서 Saga 보상(재고 복구 + 주문 취소)이 실제로 동작하는지 확인.
# 실제 클라이언트 경로 그대로 api-gateway(GW)를 거쳐서 호출한다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "3. 결제 실패 강제 -> Saga 보상(재고 복구 + CANCELLED)"

STOCK_BEFORE=$(curl -s "${DIRECT_INVENTORY}/inventory/products/2/stock" | grep -o '"availableStock":[0-9]*' | cut -d: -f2)

RESP=$(curl -s -X POST "${GW}/orders" -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":true}')
echo "$RESP"
STATUS=$(echo "$RESP" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
REASON=$(echo "$RESP" | grep -o '"failureReason":"[^"]*"' | cut -d'"' -f4)

check "결제 실패 시 CANCELLED" "CANCELLED" "$STATUS" || FAIL=1
check "취소 사유 PAYMENT_FAILED" "PAYMENT_FAILED" "$REASON" || FAIL=1

sleep 1
STOCK_AFTER=$(curl -s "${DIRECT_INVENTORY}/inventory/products/2/stock" | grep -o '"availableStock":[0-9]*' | cut -d: -f2)
check "재고 복구됨 (차감 전 수량으로 복귀)" "$STOCK_BEFORE" "$STOCK_AFTER" || FAIL=1

exit $FAIL
