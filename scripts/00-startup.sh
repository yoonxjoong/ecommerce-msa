#!/bin/bash
# 전체 스택을 빌드해서 띄우고, 콜드 스타트가 끝날 때까지 기다린다.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

section "0. 전체 스택 기동"
docker compose up -d --build
echo "기동 대기 중 (60초 - 메모리 압박 환경에서 콜드 스타트가 느릴 수 있음)..."
sleep 60
docker compose ps
