#!/bin/bash
# api-gateway의 Rate Limiting: 짧은 시간에 몰아치면 버스트 허용치를 넘겨서 429가 나와야 한다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "5. Rate Limiting - 짧은 시간에 몰아치면 429가 나와야 함"

CODES=""
for i in $(seq 1 10); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${GW}/orders" -H "Content-Type: application/json" \
    -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}')
  CODES="${CODES}${CODE} "
done
echo "응답 코드들: ${CODES}"

if echo "$CODES" | grep -q "429"; then
  echo "PASS: Rate Limiting 발동 확인 (429 포함)"
else
  echo "FAIL: 10건 연속 요청에 429가 하나도 없음"
  FAIL=1
fi

exit $FAIL
