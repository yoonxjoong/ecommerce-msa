#!/bin/bash
# 아래 파일들을 순서대로 전부 실행한다. 컨테이너를 내렸다 올렸다 하는 파괴적인
# 테스트(Circuit Breaker, Inbox 재전달)가 섞여있어서, 순서를 지키며 처음부터
# 끝까지 돌리는 걸 전제로 한다 - 중간 파일만 따로 돌리고 싶으면 그 전 단계까지는
# 스택이 이미 정상 기동돼 있어야 한다.
set -uo pipefail
cd "$(dirname "$0")/.."

SCRIPTS=(
  scripts/00-startup.sh
  scripts/01-cache-test.sh
  scripts/02-normal-order-test.sh
  scripts/03-saga-compensation-test.sh
  scripts/oversell-test.sh
  scripts/05-rate-limit-test.sh
  scripts/queue-test.sh
  scripts/07-outbox-kafka-test.sh
  scripts/08-inbox-dedup-test.sh
  scripts/09-circuit-breaker-test.sh
  scripts/10-reconciliation-test.sh
)

FAIL=0
for script in "${SCRIPTS[@]}"; do
  "./${script}"
  if [ $? -ne 0 ]; then
    FAIL=1
  fi
done

echo
echo "=================================================="
if [ "$FAIL" -eq 0 ]; then
  echo "PASS: 전체 시스템 검증 통과"
else
  echo "FAIL: 위에서 FAIL로 표시된 항목을 확인하세요"
fi
echo "=================================================="
echo
echo "확인 끝. 스택을 내리려면: docker compose down"

exit $FAIL
