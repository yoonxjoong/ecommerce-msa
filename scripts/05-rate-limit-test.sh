#!/bin/bash
# api-gateway의 Rate Limiting: 짧은 시간에 몰아치면 버스트 허용치(10)를 넘겨서
# 429가 나와야 한다. 일부러 버킷을 바닥까지 긁어내는 테스트라, 끝나고 나서
# 충분히 쉬어서 뒤에 오는 다른 테스트가 고갈된 버킷 때문에 오탐나지 않게 한다
# (run-all.sh에서도 이 테스트를 맨 뒤로 돌려서 이중으로 안전장치를 둠).
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "5. Rate Limiting - 짧은 시간에 몰아치면 429가 나와야 함"

CODES=""
for i in $(seq 1 20); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${GW}/orders" -H "Content-Type: application/json" \
    -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}')
  CODES="${CODES}${CODE} "
done
echo "응답 코드들: ${CODES}"

if echo "$CODES" | grep -q "429"; then
  echo "PASS: Rate Limiting 발동 확인 (429 포함)"
else
  echo "FAIL: 20건 연속 요청에 429가 하나도 없음"
  FAIL=1
fi

echo "토큰 버킷 회복 대기 중 (3초)..."
sleep 3

exit $FAIL
