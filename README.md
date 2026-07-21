# ecommerce-msa

[이커머스 아키텍처 학습 노트](https://yoonxjoong.github.io)에서 다룬 재고 동시성 제어(Redis Lua 원자 연산)와
데이터 정합성(Outbox 패턴 + Saga 보상 트랜잭션)을 검증해보기 위한 작은 구현체입니다.
글의 세 축(트래픽/동시성/정합성) 중 **동시성 제어 + 정합성** 핵심 흐름만 우선 구현했습니다.

## 구성

- **order-service** (8080): 주문 생성, Saga 오케스트레이터
- **inventory-service** (8081): 상품/재고, Redis Lua 스크립트로 재고 확인+차감 원자 처리
- **payment-service** (8082): mock 결제 승인, Outbox 패턴으로 이벤트를 Kafka에 발행
- **reconciliation-batch** (포트 없음): Redis 재고 카운터를 주기적으로 Postgres `product.stock_quantity`에 되돌려 쓰는 배치 (5초 주기)

## 흐름

1. `POST /orders` → order-service가 주문을 PENDING으로 생성
2. inventory-service에 재고 확인+차감 요청 (Redis Lua, 원자 연산) — 실패하면 주문 CANCELLED(OUT_OF_STOCK)
3. payment-service에 결제 요청 (mock PG, Idempotency Key로 중복 방지)
4. 결제 성공 → 주문 CONFIRMED
5. 결제 실패 → **Saga 보상**: inventory-service에 재고 복구 요청 후 주문 CANCELLED(PAYMENT_FAILED)
6. payment-service는 결제 승인/실패와 Outbox 이벤트 기록을 같은 트랜잭션으로 묶고, 별도 스케줄러(Message Relay)가 주기적으로 읽어 Kafka(`payment-events`)로 발행
7. reconciliation-batch가 5초마다 Redis의 `stock:product:*` 값을 읽어 Postgres `product.stock_quantity`와 다르면 갱신 — Redis가 데이터를 잃어도(재시작 등) inventory-service가 "최초 시딩값"이 아니라 "마지막으로 동기화된 값"으로 복구되게 함

## 실행

```bash
docker compose up --build
```

기동 후:

```bash
curl http://localhost:8081/inventory/products
```

## 시드 데이터

- 상품 1: 한정판 스니커즈, 재고 3개 (오버셀링 테스트용으로 일부러 적게 설정)
- 상품 2: 무선 이어폰, 재고 100개

## 주문 생성 예시

```bash
# 정상 주문
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}'

# 결제 실패 강제 (Saga 보상 확인용)
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":true}'
```

## 오버셀링 테스트

재고 3개인 상품 1에 동시 요청 10개를 던져서, CONFIRMED가 정확히 3건만 나오는지 확인합니다.

```bash
./scripts/oversell-test.sh
```

## 재고 재동기화 (reconciliation-batch)

Redis는 이 프로젝트에서 사실상 유일한 실시간 재고 소스입니다. 문제는 Redis가 재시작 등으로 데이터를 잃으면,
inventory-service의 `seedStockCounters()`가 Postgres의 **최초 시딩값**으로 다시 채워버려서 이미 판매된
재고가 되살아나는(오버셀링) 사고로 이어진다는 점입니다.

`reconciliation-batch`는 이 문제를 완전히 없애지는 못하지만 위험 구간을 줄입니다:

- Redis에 AOF 영속성(`--appendonly yes`)을 켜서, 정상적인 컨테이너 재시작으로는 데이터가 아예 안 날아가게 함
- 그래도 데이터가 유실되는 최악의 경우를 대비해, 5초마다 Redis → Postgres로 현재 값을 되돌려 써서
  DB가 "최초값"이 아니라 "마지막으로 동기화된 값"을 갖고 있게 함

재동기화 주기(5초)와 실제 유실 사이 사이의 간극만큼은 여전히 위험 구간으로 남아있습니다 — 즉 이 방식은
위험을 줄이는 완화책이지, 완전한 해결책은 아닙니다.

## 알려진 한계 (블로그 글의 "한계 및 남는 궁금증"과 동일한 지점)

- 장바구니 단계, 캐싱(트래픽 대응 섹션)은 이번 1단계 범위에 포함하지 않았습니다.
- Circuit Breaker, 알림/정산 서비스는 구현하지 않았고, Kafka로 발행된 `payment-events`를 소비하는 컨슈머도 아직 없습니다 (Outbox → Kafka 발행까지만 확인 가능).
- 여러 상품이 섞인 주문의 부분 실패 처리는 다루지 않았습니다 (주문당 상품 1종만 지원).
- reconciliation-batch는 재동기화 주기(5초) 안에 발생하는 유실은 막지 못합니다 (위 설명 참고).
