#!/bin/bash
# Outbox -> Message Relay -> Kafka -> notification-service까지 파이프라인이 끝까지
# 이어지는지, 새 주문 하나로 알림 로그가 실제로 늘어나는지 확인.
# 실제 클라이언트 경로 그대로 api-gateway(GW)를 거쳐서 호출한다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "7. Outbox -> Kafka -> notification-service 소비 확인"

BEFORE_COUNT=$(docker logs ecommerce-msa-notification-service-1 2>&1 | grep -c "\[알림\]" || true)

RESP=$(curl -s -X POST "${GW}/orders" -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}')
STATUS=$(echo "$RESP" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)

if [ "$STATUS" != "CONFIRMED" ]; then
  echo "FAIL: 이 테스트용 주문 자체가 실패함 ($RESP) - 결제 이벤트가 안 만들어졌으니 아래 확인은 의미 없음"
  exit 1
fi

sleep 5
AFTER_COUNT=$(docker logs ecommerce-msa-notification-service-1 2>&1 | grep -c "\[알림\]" || true)

if [ "$AFTER_COUNT" -gt "$BEFORE_COUNT" ]; then
  echo "PASS: notification-service가 새 이벤트를 소비함 (${BEFORE_COUNT} -> ${AFTER_COUNT})"
else
  echo "FAIL: notification-service 로그가 늘지 않음"
  FAIL=1
fi

exit $FAIL
