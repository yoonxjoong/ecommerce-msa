#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."

PRODUCT_ID=1
INITIAL_STOCK=3
CONCURRENT_REQUESTS=10

echo "Redis 재고를 ${INITIAL_STOCK}개로 리셋합니다 (product ${PRODUCT_ID})"
docker compose exec -T redis redis-cli SET "stock:product:${PRODUCT_ID}" "${INITIAL_STOCK}" > /dev/null

echo "동시 주문 ${CONCURRENT_REQUESTS}건을 상품 ${PRODUCT_ID}(재고 ${INITIAL_STOCK})에 던집니다..."

RESULT_DIR=$(mktemp -d)
pids=()
for i in $(seq 1 "$CONCURRENT_REQUESTS"); do
  (
    curl -s -X POST http://localhost:8080/orders \
      -H "Content-Type: application/json" \
      -d "{\"userId\":${i},\"productId\":${PRODUCT_ID},\"quantity\":1,\"simulateFailure\":false}" \
      > "${RESULT_DIR}/result_${i}.json"
  ) &
  pids+=($!)
done

for pid in "${pids[@]}"; do
  wait "$pid"
done

echo
echo "응답 목록:"
cat "${RESULT_DIR}"/result_*.json
echo

CONFIRMED=$(grep -o '"status":"CONFIRMED"' "${RESULT_DIR}"/result_*.json | wc -l)
OUT_OF_STOCK=$(grep -o '"OUT_OF_STOCK"' "${RESULT_DIR}"/result_*.json | wc -l)

echo "CONFIRMED: ${CONFIRMED}건 (기대값: ${INITIAL_STOCK}건)"
echo "OUT_OF_STOCK: ${OUT_OF_STOCK}건"

FINAL_STOCK=$(curl -s "http://localhost:8081/inventory/products/${PRODUCT_ID}/stock" | grep -o '"availableStock":[0-9-]*' | cut -d: -f2)
echo "최종 Redis 재고: ${FINAL_STOCK}"

rm -rf "${RESULT_DIR}"

if [ "${CONFIRMED}" -eq "${INITIAL_STOCK}" ]; then
  echo "PASS: 오버셀링 없이 정확히 재고 수량만큼만 확정되었습니다."
else
  echo "FAIL: CONFIRMED 건수(${CONFIRMED})가 재고(${INITIAL_STOCK})와 일치하지 않습니다."
  exit 1
fi
