#!/bin/bash
# payment-service를 실제로 내려서 Circuit Breaker가 OPEN으로 전환되는지 확인한다.
#
# 예전엔 응답을 못 받으면 그 자리에서 바로 PAYMENT_SERVICE_UNAVAILABLE로 취소했는데,
# 이제는 "응답을 못 받은 것"과 "실제로 결제가 실패한 것"을 구분한다 - payment-service가
# 실제로는 처리했는데 응답만 유실됐을 수도 있기 때문에, 바로 취소하지 않고 PENDING으로
# 남겨서 payment-events 이벤트(PaymentEventListener)나 PENDING 타임아웃 안전망
# (PendingOrderTimeoutSweeper)이 나중에 정리하게 한다. 이 테스트는 그 흐름 전체를 확인한다.
#
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

PENDING_ORDER_IDS=""
STATUSES=""
for i in $(seq 1 8); do
  RESP=$(curl -s -X POST "${GW}/orders" -H "Content-Type: application/json" \
    -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}')
  ID=$(echo "$RESP" | grep -o '"id":[0-9]*' | cut -d: -f2)
  STATUS=$(echo "$RESP" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
  STATUSES="${STATUSES}${STATUS} "
  PENDING_ORDER_IDS="${PENDING_ORDER_IDS}${ID} "
done
echo "응답 상태들: ${STATUSES}"

ALL_PENDING=1
for s in $STATUSES; do
  if [ "$s" != "PENDING" ]; then
    ALL_PENDING=0
  fi
done
if [ "$ALL_PENDING" -eq 1 ]; then
  echo "PASS: 응답을 못 받은 8건 모두 즉시 취소되지 않고 PENDING으로 남음"
else
  echo "FAIL: PENDING이 아닌 응답이 섞여있음 (${STATUSES})"
  FAIL=1
fi

TRANSITION_AFTER=$(docker logs ecommerce-msa-order-service-1 2>&1 | grep -c "CLOSED -> OPEN" || true)
if [ "$TRANSITION_AFTER" -gt "$TRANSITION_BEFORE" ]; then
  echo "PASS: Circuit Breaker 상태 전환 로그 확인 (CLOSED -> OPEN, 이번 테스트 중 새로 발생)"
else
  echo "FAIL: 상태 전환 로그가 이번 테스트 중에 새로 찍히지 않음"
  FAIL=1
fi

echo "PENDING 타임아웃 안전망(기본 30초 + 스윕주기 10초) 대기 중 (45초)..."
echo "이 8건은 payment-service가 요청 자체를 못 받았으므로 payment-events가 절대 안 오고,"
echo "PendingOrderTimeoutSweeper만이 이 주문들을 정리할 수 있다."
sleep 45

TIMEOUT_REASONS=""
for id in $PENDING_ORDER_IDS; do
  RESP=$(curl -s "${GW}/orders/${id}")
  REASON=$(echo "$RESP" | grep -o '"failureReason":"[^"]*"' | cut -d'"' -f4)
  TIMEOUT_REASONS="${TIMEOUT_REASONS}${REASON} "
done
echo "취소 사유들: ${TIMEOUT_REASONS}"

if echo "$TIMEOUT_REASONS" | grep -q "PAYMENT_TIMEOUT"; then
  echo "PASS: PENDING 타임아웃 안전망이 정상 동작 (PAYMENT_TIMEOUT으로 정리됨)"
else
  echo "FAIL: PAYMENT_TIMEOUT 사유를 못 봄"
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
