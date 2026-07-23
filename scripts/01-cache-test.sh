#!/bin/bash
# 상품 조회 캐싱(Cache-Aside) 검증: 같은 상품을 여러 번 조회해도 DB는 한 번만 타야 한다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "1. 캐싱 - 상품 조회는 DB를 한 번만 타야 함"

curl -s "${DIRECT_INVENTORY}/inventory/products/2" > /dev/null
curl -s "${DIRECT_INVENTORY}/inventory/products/2" > /dev/null
curl -s "${DIRECT_INVENTORY}/inventory/products/2" > /dev/null

MISS_COUNT=$(docker logs ecommerce-msa-inventory-service-1 2>&1 | grep -c "\[Cache Miss\] DB에서 상품 2 조회" || true)
if [ "$MISS_COUNT" -le 1 ]; then
  echo "PASS: 캐시 히트 확인 (Cache Miss 로그 ${MISS_COUNT}회, 3번 조회 중 1회 이하)"
else
  echo "FAIL: 캐시가 안 먹는 것 같음 (Cache Miss 로그 ${MISS_COUNT}회)"
  FAIL=1
fi

exit $FAIL
