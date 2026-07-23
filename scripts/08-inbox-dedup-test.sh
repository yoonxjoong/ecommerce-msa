#!/bin/bash
# Inbox 패턴 검증: 컨슈머 그룹 오프셋을 강제로 처음으로 되돌려서 이미 처리한
# 이벤트들을 전부 재전달시키고, 중복 처리 없이 스킵되는지 확인한다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "8. Inbox 패턴 - 컨슈머 그룹 오프셋을 되돌려 강제 재전달"

docker compose stop notification-service > /dev/null 2>&1

docker exec ecommerce-msa-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group notification-service \
  --topic payment-events --reset-offsets --to-earliest --execute > /dev/null

docker compose start notification-service > /dev/null 2>&1
sleep 15

if docker logs ecommerce-msa-notification-service-1 2>&1 | grep -q "이미 처리한 이벤트라 건너뜁니다"; then
  echo "PASS: Inbox 패턴이 재전달된 이벤트를 중복 처리하지 않음"
else
  echo "FAIL: '이미 처리한 이벤트' 로그를 못 찾음"
  FAIL=1
fi

exit $FAIL
