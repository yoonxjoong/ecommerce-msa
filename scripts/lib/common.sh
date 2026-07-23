# 다른 테스트 스크립트들이 공통으로 쓰는 변수/함수 모음. source해서 쓴다 (직접 실행하는 파일 아님).

GW="http://localhost:8090"
DIRECT_INVENTORY="http://localhost:8081"

# 모든 주문 요청은 실제 클라이언트 경로 그대로 GW(api-gateway)를 거친다.
# GW의 Rate Limiter(초당 10개, 버스트 10)는 이 테스트 스위트 전체가 자연스럽게
# 흩어져서 보내는 요청은 다 흡수할 만큼 여유를 뒀고, 그래도 Rate Limiting을
# 일부러 터뜨리는 05번/오버셀링 테스트는 run-all.sh에서 맨 뒤로 몰아서 다른
# 테스트가 그 여파(토큰 버킷 고갈)를 맞지 않게 했다.

# 사용법: check "설명" "기대값" "실제값"  -> 다르면 return 1
check() {
  local description=$1 expected=$2 actual=$3
  if [ "$expected" = "$actual" ]; then
    echo "PASS: ${description} (${actual})"
    return 0
  else
    echo "FAIL: ${description} (기대값 ${expected}, 실제 ${actual})"
    return 1
  fi
}

section() {
  echo
  echo "=================================================="
  echo "$1"
  echo "=================================================="
}
