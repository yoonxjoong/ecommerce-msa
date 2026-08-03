#!/bin/bash
# Bulkhead(동시 호출 10건 제한)가 실제로 초과분을 막는지 확인한다.
#
# payment-service를 죽이는(stop) 게 아니라 "일시정지(pause)"시킨다 - 프로세스를
# 얼려서 응답을 아예 못 하게 만드는 것. 이러면 호출이 "빠르게 실패"하는 게 아니라
# "응답 없이 계속 매달려있다가 타임아웃"하게 되어서, Bulkhead가 없으면 죄다 똑같이
# 오래 걸리다 실패해야 정상이다.
#
# 여기에 동시 요청 15건을 쏜다. Bulkhead(max-concurrent-calls: 10) 설정대로면:
#   - 먼저 들어간 10건은 실제로 payment-service에 붙어서 응답을 기다리다 타임아웃(2~3초대)
#   - 나머지 5건은 슬롯이 꽉 차서 시도조차 못 해보고 즉시(0.x초) 튕겨나감
# 그래서 "즉시 실패한 건수"를 세어보면 Bulkhead가 실제로 동작하는지 알 수 있다.
#
# api-gateway(8090)가 아니라 order-service(8080)에 직접 쏜다 - gateway를 거치면
# Rate Limiter(초당 10개)가 같이 걸려서 "빠른 실패"의 원인이 Bulkhead인지
# Rate Limiter인지 구분이 안 된다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAIL=0

section "11. Bulkhead - payment-service 응답 지연 상황에서 동시 호출 제한"

CONCURRENT_REQUESTS=15
DIRECT_ORDER="http://localhost:8080"

echo "payment-service를 일시정지시킵니다 (kill이 아니라 pause - 응답만 못 하게)..."
docker compose pause payment-service > /dev/null 2>&1

RESULT_DIR=$(mktemp -d)
pids=()
for i in $(seq 1 "$CONCURRENT_REQUESTS"); do
  (
    # curl -w로 요청 하나가 실제로 몇 초 걸렸는지를 응답 바디 뒤에 이어붙여서 파일로 저장.
    # 이 시간이 "즉시 실패(Bulkhead)"와 "타임아웃까지 기다림(진짜 시도)"을 구분하는 근거가 된다.
    curl -s -w "\n%{time_total}" -X POST "${DIRECT_ORDER}/orders" \
      -H "Content-Type: application/json" \
      -d "{\"userId\":${i},\"productId\":2,\"quantity\":1,\"simulateFailure\":false}" \
      > "${RESULT_DIR}/result_${i}.txt"
  ) &
  pids+=($!)
done

# 백그라운드로 던진 15개 요청이 전부 끝날 때까지 기다린다.
for pid in "${pids[@]}"; do
  wait "$pid"
done

echo "payment-service 재개..."
docker compose unpause payment-service > /dev/null 2>&1

FAST=0   # 0.5초 미만 - Bulkhead에 바로 튕긴 것으로 추정
SLOW=0   # 1.5초 이상 - 실제로 시도했다가 타임아웃난 것으로 추정
for i in $(seq 1 "$CONCURRENT_REQUESTS"); do
  TIME=$(tail -n1 "${RESULT_DIR}/result_${i}.txt")
  # awk로 부동소수점 비교 (bash [ ]는 정수만 비교 가능해서 이렇게 우회)
  IS_FAST=$(awk -v t="$TIME" 'BEGIN { print (t < 0.5) ? 1 : 0 }')
  if [ "$IS_FAST" -eq 1 ]; then
    FAST=$((FAST + 1))
  else
    SLOW=$((SLOW + 1))
  fi
done

echo "즉시 실패(Bulkhead로 추정): ${FAST}건 / 시도 후 타임아웃(실제 호출): ${SLOW}건"

check "동시 ${CONCURRENT_REQUESTS}건 중 즉시 튕긴 건수 5건 (Bulkhead 슬롯 10 초과분)" "5" "$FAST" || FAIL=1

rm -rf "${RESULT_DIR}"
exit $FAIL
