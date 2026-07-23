#!/bin/bash
# 상품 조회 캐싱(Cache-Aside) 검증: 같은 상품을 여러 번 조회해도 DB는 한 번만 타야 한다.
# docker logs는 컨테이너 시작 이후 누적 로그라, 이전 실행에서 쌓인 Cache Miss까지
# 다 잡히지 않도록 반드시 "호출 전후 차이"로 비교한다 (절대값 카운트 금지).
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "1. 캐싱 - 상품 조회는 DB를 한 번만 타야 함"

BEFORE_COUNT=$(docker logs ecommerce-msa-inventory-service-1 2>&1 | grep -c "\[Cache Miss\] DB에서 상품 2 조회" || true)

curl -s "${DIRECT_INVENTORY}/inventory/products/2" > /dev/null
curl -s "${DIRECT_INVENTORY}/inventory/products/2" > /dev/null
curl -s "${DIRECT_INVENTORY}/inventory/products/2" > /dev/null

AFTER_COUNT=$(docker logs ecommerce-msa-inventory-service-1 2>&1 | grep -c "\[Cache Miss\] DB에서 상품 2 조회" || true)
NEW_MISSES=$((AFTER_COUNT - BEFORE_COUNT))

if [ "$NEW_MISSES" -le 1 ]; then
  echo "PASS: 캐시 히트 확인 (이번 호출 중 Cache Miss ${NEW_MISSES}회, 3번 조회 중 1회 이하)"
else
  echo "FAIL: 캐시가 안 먹는 것 같음 (이번 호출 중 Cache Miss ${NEW_MISSES}회)"
  FAIL=1
fi

exit $FAIL
