# 다른 테스트 스크립트들이 공통으로 쓰는 변수/함수 모음. source해서 쓴다 (직접 실행하는 파일 아님).

GW="http://localhost:8090"
DIRECT_INVENTORY="http://localhost:8081"

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
