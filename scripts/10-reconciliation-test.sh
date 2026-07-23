#!/bin/bash
# Redis 재고 값을 일부러 조작해서, reconciliation-batch가 그 값을 실제로
# Postgres에 되돌려 쓰는지(5초 주기) 확인한다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "10. 재고 재동기화 배치 - Redis 값이 Postgres에 반영되는지"

docker exec ecommerce-msa-redis-1 redis-cli SET "stock:product:2" "77" > /dev/null
echo "Redis 재고를 77로 강제 변경, 재동기화 주기(5초) + 여유 대기..."
sleep 8

DB_VALUE=$(docker exec ecommerce-msa-postgres-1 psql -U postgres -d inventory_db -tAc \
  "SELECT stock_quantity FROM product WHERE id = 2;")
check "Postgres가 Redis 값(77)으로 재동기화됨" "77" "$DB_VALUE" || FAIL=1

exit $FAIL
