#!/bin/bash
# payment-service를 실제로 내려서 Circuit Breaker가 OPEN으로 전환되는지,
# 그동안 주문이 PAYMENT_SERVICE_UNAVAILABLE로 빠르게 취소되는지,
# 복구 후 다시 정상 주문이 되는지(HALF_OPEN -> CLOSED)까지 확인.
# 실제 클라이언트 경로 그대로 api-gateway(GW)를 거쳐서 호출한다. 8번 요청해서
# Circuit Breaker의 minimum-number-of-calls(5)를 여유 있게 채운다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "9. Circuit Breaker - payment-service 다운 시뮬레이션"

docker compose stop payment-service > /dev/null 2>&1
echo "payment-service 중지함. 연속 주문 요청 중..."

TRANSITION_BEFORE=$(docker logs ecommerce-msa-order-service-1 2>&1 | grep -c "CLOSED -> OPEN" || true)

CB_REASONS=""
for i in $(seq 1 8); do
  RESP=$(curl -s -X POST "${GW}/orders" -H "Content-Type: application/json" \
    -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}')
  REASON=$(echo "$RESP" | grep -o '"failureReason":"[^"]*"' | cut -d'"' -f4)
  CB_REASONS="${CB_REASONS}${REASON} "
done
echo "취소 사유들: ${CB_REASONS}"

if echo "$CB_REASONS" | grep -q "PAYMENT_SERVICE_UNAVAILABLE"; then
  echo "PASS: Circuit Breaker가 OPEN되어 PAYMENT_SERVICE_UNAVAILABLE로 빠르게 취소됨"
else
  echo "FAIL: PAYMENT_SERVICE_UNAVAILABLE 사유를 못 봄"
  FAIL=1
fi

TRANSITION_AFTER=$(docker logs ecommerce-msa-order-service-1 2>&1 | grep -c "CLOSED -> OPEN" || true)
if [ "$TRANSITION_AFTER" -gt "$TRANSITION_BEFORE" ]; then
  echo "PASS: Circuit Breaker 상태 전환 로그 확인 (CLOSED -> OPEN, 이번 테스트 중 새로 발생)"
else
  echo "FAIL: 상태 전환 로그가 이번 테스트 중에 새로 찍히지 않음"
  FAIL=1
fi

echo "payment-service 재기동, 회복 대기 중 (30초 - 서비스 자체 기동 시간 + Circuit OPEN 유지시간 10초)..."
docker compose start payment-service > /dev/null 2>&1
sleep 30

RESP=$(curl -s -X POST "${GW}/orders" -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}')
echo "$RESP"
STATUS=$(echo "$RESP" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
check "회복 후 정상 주문 CONFIRMED" "CONFIRMED" "$STATUS" || FAIL=1

exit $FAIL
