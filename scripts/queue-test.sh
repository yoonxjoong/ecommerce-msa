#!/bin/bash
# 가상 대기열 시나리오를 처음부터 끝까지 자동으로 검증한다:
# 평소 통과 -> 활성화 후 토큰 없이 거부 -> 진입 -> 입장 대기 -> 토큰으로 성공
# -> 같은 토큰 재사용 거부 -> 비활성화 후 다시 통과.
set -uo pipefail

cd "$(dirname "$0")/.."

BASE_URL="http://localhost:8090"
PRODUCT_ID=1
FAIL=0

# 이 스크립트는 상품 1 재고를 최대 3개까지 실제로 소비한다(정상 주문 2번 + 토큰 주문 1번).
# 이전 실행에서 재고가 바닥났을 수 있으니, 매번 넉넉하게 리셋하고 시작한다.
docker exec ecommerce-msa-redis-1 redis-cli SET "stock:product:${PRODUCT_ID}" "100" > /dev/null

check() {
  local description=$1
  local expected=$2
  local actual=$3
  if [ "$expected" = "$actual" ]; then
    echo "PASS: ${description} (${actual})"
  else
    echo "FAIL: ${description} (기대값 ${expected}, 실제 ${actual})"
    FAIL=1
  fi
}

echo "=== 0) 대기열 비활성 상태 - 토큰 없이 주문하면 통과해야 함 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/orders" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":1,\"productId\":${PRODUCT_ID},\"quantity\":1,\"simulateFailure\":false}")
check "비활성 상태 주문 통과" "200" "$CODE"

echo
echo "=== 1) 상품 ${PRODUCT_ID} 대기열 모드 활성화 ==="
curl -s -o /dev/null -X POST "${BASE_URL}/admin/queue/${PRODUCT_ID}/enable"

echo
echo "=== 2) 활성 상태 - 토큰 없이 주문하면 거부(403)돼야 함 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/orders" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":1,\"productId\":${PRODUCT_ID},\"quantity\":1,\"simulateFailure\":false}")
check "토큰 없는 주문 거부" "403" "$CODE"

echo
echo "=== 3) 대기열 진입 ==="
ENTER_RESP=$(curl -s -X POST "${BASE_URL}/queue/${PRODUCT_ID}/enter")
echo "$ENTER_RESP"
TICKET_ID=$(echo "$ENTER_RESP" | grep -o '"ticketId":"[^"]*"' | cut -d'"' -f4)

echo
echo "=== 4) 입장(admission) 될 때까지 최대 10초 폴링 ==="
TOKEN=""
for i in $(seq 1 10); do
  sleep 1
  STATUS_RESP=$(curl -s "${BASE_URL}/queue/${PRODUCT_ID}/status/${TICKET_ID}")
  if echo "$STATUS_RESP" | grep -q '"admitted":true'; then
    TOKEN=$(echo "$STATUS_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
    echo "${i}초 후 입장 확인: ${STATUS_RESP}"
    break
  fi
done

if [ -z "$TOKEN" ]; then
  echo "FAIL: 10초 안에 입장하지 못했습니다"
  FAIL=1
else
  echo
  echo "=== 5) 발급받은 토큰으로 주문 -> 성공(200)해야 함 ==="
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/orders" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":1,\"productId\":${PRODUCT_ID},\"quantity\":1,\"simulateFailure\":false,\"queueToken\":\"${TOKEN}\"}")
  check "유효 토큰 주문 성공" "200" "$CODE"

  echo
  echo "=== 6) 같은 토큰 재사용 -> 거부(403)돼야 함 ==="
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/orders" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":1,\"productId\":${PRODUCT_ID},\"quantity\":1,\"simulateFailure\":false,\"queueToken\":\"${TOKEN}\"}")
  check "토큰 재사용 거부" "403" "$CODE"
fi

echo
echo "=== 7) 대기열 모드 비활성화 -> 다시 토큰 없이 통과해야 함 ==="
curl -s -o /dev/null -X POST "${BASE_URL}/admin/queue/${PRODUCT_ID}/disable"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/orders" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":1,\"productId\":${PRODUCT_ID},\"quantity\":1,\"simulateFailure\":false}")
check "비활성화 후 주문 통과" "200" "$CODE"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "PASS: 가상 대기열 시나리오 전체 통과"
else
  echo "FAIL: 위에서 실패한 항목을 확인하세요"
  exit 1
fi
