# ecommerce-msa

[이커머스 아키텍처 학습 노트](https://yoonxjoong.github.io)에서 다룬 재고 동시성 제어(Redis Lua 원자 연산),
데이터 정합성(Outbox 패턴 + Saga 보상 트랜잭션), 트래픽 대응(캐싱 + Rate Limiting + 가상 대기열)을 검증해보기 위한 작은 구현체입니다.
글의 세 축(트래픽/동시성/정합성) 중 CDN만 아직 설계 수준으로 남아있고 나머지는 구현했습니다 (CDN은 정적 자산이 있어야 의미가 있는데, 이 프로젝트엔 프론트엔드/정적 파일이 없어서 범위에서 뺐습니다).

## 구성

- **api-gateway** (8090): Spring Cloud Gateway. `POST /orders`(구매하기)에 Redis 기반 Token Bucket Rate Limiting 적용, 나머지는 각 서비스로 라우팅만
- **order-service** (8080): 주문 생성, Saga 오케스트레이터. `payment-events`도 구독해서(Inbox 패턴) 응답을 못 받았던 결제의 최종 확정/취소를 처리하고, PENDING 타임아웃 안전망 스케줄러도 갖고 있음
- **inventory-service** (8081): 상품/재고, Redis Lua 스크립트로 재고 확인+차감 원자 처리(멱등키 지원), 상품 조회는 Redis Cache-Aside(TTL 30초)
- **payment-service** (8082): mock 결제 승인, Outbox 패턴으로 이벤트를 Kafka에 발행
- **notification-service** (포트 없음): Kafka `payment-events`를 구독해서 결제 완료/실패 알림을 로그로 남기는 컨슈머
- **waiting-room-service** (8091): 특정 상품을 "대기열 모드"로 켜두면, 그 상품 주문 시 입장 토큰이 필요하게 만드는 가상 대기열
- **reconciliation-batch** (포트 없음): Redis 재고 카운터를 주기적으로 Postgres `product.stock_quantity`에 되돌려 쓰는 배치 (5초 주기)

## 전체 검증 스크립트

지금까지 다룬 패턴을 전부 순서대로 확인하는 스크립트 모음입니다. `scripts/lib/common.sh`에 공통 함수(`check`, `section`)를 모아두고, 각 파일은 시나리오 하나씩만 담당합니다.

```bash
./scripts/run-all.sh   # 전부 순서대로 실행
```

| 파일 | 확인하는 것 |
| --- | --- |
| `00-startup.sh` | 전체 스택 빌드+기동 |
| `01-cache-test.sh` | 상품 조회 캐싱 (DB 접근 1회 이하) |
| `02-normal-order-test.sh` | 정상 주문 → CONFIRMED |
| `03-saga-compensation-test.sh` | 결제 실패 → 재고 복구 + CANCELLED |
| `oversell-test.sh` | 오버셀링 방지 (동시 요청 10건 → 재고 3개만 CONFIRMED) |
| `05-rate-limit-test.sh` | Rate Limiting (10건 연속 요청 중 429 발생) |
| `queue-test.sh` | 가상 대기열 전체 생명주기 |
| `07-outbox-kafka-test.sh` | Outbox → Kafka → notification-service 소비 |
| `08-inbox-dedup-test.sh` | Inbox 패턴 (오프셋 리셋 후 중복 처리 안 함) |
| `09-circuit-breaker-test.sh` | Circuit Breaker (payment-service 다운 → PENDING 유지 → 타임아웃 정리 → 복구) |
| `10-reconciliation-test.sh` | 재고 재동기화 배치 (Redis → Postgres) |
| `11-bulkhead-test.sh` | Bulkhead (동시 15건 중 10건만 실제 호출, 나머지는 즉시 거부) |

Circuit Breaker/Inbox 테스트는 컨테이너를 직접 내렸다 올리는 파괴적인 테스트라, 순서를 지키며 처음부터 끝까지 돌리는 걸 전제로 합니다. `run-all.sh` 없이 파일 하나만 따로 돌리고 싶으면, 그 전 단계까지 스택이 이미 기동돼 있어야 합니다.

## 흐름

1. `POST /orders` → order-service가 주문을 PENDING으로 생성
2. inventory-service에 재고 확인+차감 요청 (Redis Lua, 원자 연산, 멱등키로 재시도 시 이중 차감 방지) — 실패하면 주문 CANCELLED(OUT_OF_STOCK)
3. payment-service에 결제 요청 (mock PG, Idempotency Key를 `Idempotency-Key` 헤더로 전달해 중복 방지, Retry+CircuitBreaker+Bulkhead로 감쌈)
4. 응답을 **확실히 받은 경우**(승인 또는 PG의 명시적 거절) → 그 자리에서 바로 CONFIRMED 또는 **Saga 보상**(재고 복구 후 CANCELLED)
5. 응답 자체를 **못 받은 경우**(Circuit Open 등) → 즉시 실패로 간주하지 않고 PENDING 유지 (payment-service가 실제로는 처리했는데 응답만 유실됐을 수 있어서) — 아래 "결제 확정 하이브리드 Saga" 참고
6. payment-service는 결제 승인/실패와 Outbox 이벤트 기록을 같은 트랜잭션으로 묶고, 별도 스케줄러(Message Relay)가 주기적으로 읽어 Kafka(`payment-events`)로 발행
7. notification-service가 그 이벤트를 구독해서 결제 완료/실패에 따른 알림 로그를 남김 (실제 발송은 흉내만 냄)
8. order-service도 같은 이벤트를 구독해서, 4번에서 결정 못 하고 PENDING으로 남아있던 주문을 이때 최종 확정/취소함
9. reconciliation-batch가 5초마다 Redis의 `stock:product:*` 값을 읽어 Postgres `product.stock_quantity`와 다르면 갱신 — Redis가 데이터를 잃어도(재시작 등) inventory-service가 "최초 시딩값"이 아니라 "마지막으로 동기화된 값"으로 복구되게 함

## 실행

```bash
docker compose up --build
```

기동 후:

```bash
curl http://localhost:8081/inventory/products
```

**로컬 개발 편의 도구**

- Postgres: `localhost:5433`으로 IntelliJ/DBeaver 등에서 직접 접속 가능 (`order_db`/`inventory_db`/`payment_db`/`notification_db`, 계정 `postgres`/`postgres`). 로컬에 다른 Postgres가 떠있는 경우와 충돌 안 나게 5432가 아닌 5433으로 매핑해뒀습니다.
- Redis: `localhost:6380` (컨테이너 내부는 6379 그대로라 서비스 간 통신엔 영향 없음, 마찬가지로 로컬 Redis와의 충돌 방지용)
- Kafka: `docker compose up -d kafka-ui` 후 브라우저에서 `http://localhost:8089` — 토픽/메시지/컨슈머 그룹 랙을 GUI로 확인 가능

## 시드 데이터

- 상품 1: 한정판 스니커즈, 재고 3개 (오버셀링 테스트용으로 일부러 적게 설정)
- 상품 2: 무선 이어폰, 재고 100개

## 주문 생성 예시

order-service(8080)에 직접 호출해도 되고, api-gateway(8090)를 거쳐도 됩니다 (경로는 동일). Rate Limiting을 실제로 테스트하려면 반드시 8090(api-gateway)으로 호출해야 합니다 — order-service 8080에 직접 쏘면 게이트웨이를 안 거치므로 제한이 안 걸립니다.

```bash
# 정상 주문 (api-gateway 경유)
curl -X POST http://localhost:8090/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":2,"quantity":1,"simulateFailure":false}'

# 결제 실패 강제 (Saga 보상 확인용)
curl -X POST http://localhost:8090/orders \
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

## Rate Limiting (api-gateway)

`POST /orders`에만 Spring Cloud Gateway의 `RequestRateLimiter` + `RedisRateLimiter`를 적용했습니다. IP당 초당 10개 토큰, 버스트 허용치 10으로 설정되어 있어서(`api-gateway/src/main/resources/application.yml`), 같은 IP에서 짧은 시간에 여러 번 주문 요청을 보내면 일부는 `429 Too Many Requests`를 받습니다. (처음엔 5/5로 뒀었는데, 테스트 스위트 전체가 같은 IP에서 자연스럽게 호출을 흩뿌리는 것만으로도 종종 걸려서 10/10으로 올렸습니다 — Rate Limiting 자체를 일부러 터뜨리는 테스트는 여전히 이 값을 넘겨서 429를 재현합니다.) `RedisRateLimiter`는 내부적으로 Redis Lua 스크립트로 토큰 버킷을 구현하고 있어서, 재고 서비스에서 오버셀링을 막을 때 쓴 것과 같은 원리(Redis + Lua로 원자적 카운터 처리)가 여기서도 그대로 쓰입니다. Redis에 상태를 두기 때문에 게이트웨이를 여러 대로 늘려도 제한량이 일관되게 유지됩니다.

GET 요청(상품 조회, 주문 조회)은 이 제한을 안 받습니다 — `application.yml`의 route 정의에서 `Path=/orders` + `Method=POST` 조합에만 필터를 걸어뒀습니다.

## 알림 서비스 (notification-service)

`payment-events` 토픽을 구독해서, 결제 완료면 "배송 준비 알림", 결제 실패면 "주문 취소 알림"을 로그로 남깁니다. 실제 이메일/푸시 발송 대신 로그로 흉내만 냈는데, 실제로 주문을 넣어서 이 로그가 정확히 찍히는 것까지 확인했습니다.

```
[알림] 주문 1 결제 완료 - 배송 준비 알림을 발송합니다.
[알림] 주문 2 결제 실패 - 주문 취소 알림을 발송합니다.
```

이걸로 Outbox → Message Relay → Kafka → 컨슈머까지 파이프라인 전체가 끝까지 이어지는 걸 검증했습니다 (전에는 발행까지만 확인되고 구독자가 없었습니다).

**Inbox 패턴(중복 처리 방지)**: Kafka는 at-least-once라 같은 이벤트가 재전달될 수 있습니다(리밸런싱, 커밋 전 재시작 등). `processed_event` 테이블에 처리한 `paymentId`를 기록해두고, 이미 처리한 이벤트면 건너뜁니다 (`event_id`가 PK라서 동시 중복 처리도 DB 유니크 제약으로 막힙니다). 실제로 컨슈머 그룹 오프셋을 처음으로 되돌려서 같은 메시지를 강제로 재전달시켜봤는데, 두 번째는 "이미 처리한 이벤트라 건너뜁니다" 로그로 정확히 걸러지는 것까지 확인했습니다.

## 가상 대기열 (waiting-room-service)

블랙프라이데이 같은 특정 이벤트 때만 켜지는 걸 흉내내려고, **상품별로 대기열 모드를 토글**할 수 있게 만들었습니다. 대기열 모드가 아닌 상품은 이 로직 전체를 건너뛰고 항상 통과합니다 (평소 주문 흐름에 영향 없음).

```bash
# 상품 1을 대기열 모드로 켬 (이벤트 시작)
curl -X POST http://localhost:8090/admin/queue/1/enable

# 대기열 모드인 상품은 토큰 없이 주문하면 403
curl -X POST http://localhost:8090/orders -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":1,"quantity":1,"simulateFailure":false}'

# 대기열 진입 -> ticketId 받음
curl -X POST http://localhost:8090/queue/1/enter

# 순번 폴링 -> 입장하면 token이 채워져서 옴 (스케줄러가 3초마다 앞에서부터 admission-batch-size만큼 입장시킴)
curl http://localhost:8090/queue/1/status/{ticketId}

# 받은 토큰으로 주문 (1회용 - 검증 성공하면 즉시 폐기됨)
curl -X POST http://localhost:8090/orders -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":1,"quantity":1,"simulateFailure":false,"queueToken":"{token}"}'

# 이벤트 끝나면 다시 끔
curl -X POST http://localhost:8090/admin/queue/1/disable
```

**동작 원리**: Redis Sorted Set(`queue:waiting:{productId}`)에 진입 순서대로 쌓아두고, 스케줄러가 주기적으로 앞쪽 일부(`ZPOPMIN`)를 뽑아 짧은 TTL의 입장 토큰을 발급합니다. order-service는 주문 생성 전에 항상 waiting-room-service에 토큰 검증을 요청하는데, 상품이 대기열 모드가 아니면 즉시 통과(`valid:true`)시키고, 모드라면 토큰이 유효한지(그리고 아직 안 쓴 건지) 확인합니다.

**실제로 검증한 것**: 평소 상품 주문(토큰 없이 통과) → 상품 활성화 → 토큰 없이 주문(403) → 대기열 진입 → 3초 뒤 입장 확인(토큰 발급) → 토큰으로 주문(성공) → 같은 토큰 재사용 시도(403, 1회용 확인) → 비활성화 후 다시 토큰 없이 통과. 이 전 과정을 자동으로 확인하는 스크립트를 만들어뒀습니다:

```bash
./scripts/queue-test.sh
```

각 단계마다 기대한 HTTP 상태 코드(200/403)가 실제로 나오는지 확인하고, 하나라도 어긋나면 어느 단계인지 콕 집어서 FAIL로 표시합니다.

## Circuit Breaker + Retry + Bulkhead (order-service → payment-service)

payment-service가 느려지거나 죽었을 때 order-service의 스레드/자원이 그 응답을 기다리다 고갈되는 걸 막으려고, `PaymentClient.pay()`에 Resilience4j 세 개를 같이 붙였습니다 (`order-service/.../client/PaymentClient.java`).

```java
@Retry(name = "paymentService", fallbackMethod = "payFallback")
@CircuitBreaker(name = "paymentService")
@Bulkhead(name = "paymentService")
public PaymentResult pay(Long orderId, Long amount, String idempotencyKey, boolean simulateFailure) {
    return restClient.post()...
}
```

- **CircuitBreaker**: 최근 5번 호출 중 50% 이상 실패하면 10초간 OPEN으로 전환, 그동안은 실제 호출 없이 즉시 실패 처리.
- **Bulkhead**: payment-service 호출은 동시에 10건까지만 허용, 초과분은 대기 없이 즉시 거부 — Circuit이 아직 CLOSED라 실패율로 못 잡아내는 "그냥 느리기만 한" 상황에서도 이 호출 하나가 order-service의 자원을 무제한으로 잠식하지 못하게 막습니다.
- **Retry**: 실패하면 재시도. 처음엔 `@Retry`를 `@CircuitBreaker` 안쪽에 두고 fallback도 CircuitBreaker에 뒀었는데, 그러면 CircuitBreaker가 실패를 예외 대신 값으로 삼켜버려서 바깥의 Retry가 실패 자체를 못 보는 버그가 있었습니다 — fallback은 반드시 합성 구조의 제일 바깥쪽(Retry)에 둬야 안쪽 레이어들이 제 역할을 할 기회를 가집니다. 이 함정은 [Circuit Breaker 전용 블로그 글](https://yoonxjoong.github.io/posts/circuit-breaker-resilience4j/)과 [circuit-breaker-lab](https://github.com/yoonxjoong/circuit-breaker-lab)에 따로 정리했습니다.

**타임아웃도 같이 걸었습니다.** CircuitBreaker는 "예외(실패)가 쌓이는 걸 보고" 판단하는데, RestClient에 타임아웃이 없으면 payment-service가 응답 없이 멈춰있는 상황에서는 호출이 실패로 잡히지도 않고 그냥 무한정 기다립니다. `RestClientConfig`에서 모든 클라이언트에 connect timeout 2초, read timeout 3초를 걸어서, 응답이 안 오는 상황도 결국 예외로 터져 실패율 계산에 들어가게 했습니다.

**한계**: Resilience4j의 상태는 전부 JVM 메모리 안에 있어서, order-service를 여러 인스턴스로 늘리면 인스턴스마다 회로/슬롯 상태가 따로 놉니다 (Rate Limiting 때처럼 Redis로 상태를 공유하기가 이 구조상 더 어렵습니다).

## 결제 확정 하이브리드 Saga — 응답 유실 시 정합성 처리

Circuit Open처럼 **응답 자체를 못 받은 경우**, "실패로 간주하고 재고를 복구"해버리면 위험한 시나리오가 있습니다: payment-service는 실제로 결제를 승인해서 DB에 커밋까지 끝냈는데, 그 응답만 네트워크에서 유실된 경우입니다. 이러면 order-service는 결제가 실패했다고 착각하고 재고를 복구+주문을 취소하는데, 실제로는 결제가 성공한 상태라 정합성이 깨집니다. Idempotency Key는 "같은 요청을 두 번 보냈을 때 이중 승인되는 것"만 막아주지, 이 문제 자체는 못 막아줍니다.

그래서 응답을 확실히 받은 경우(승인/명시적 거절)만 그 자리에서 바로 처리하고, **응답을 못 받은 경우는 주문을 CANCELLED로 단정하지 않고 PENDING으로 남겨서** 두 갈래로 정리합니다.

- **`PaymentEventListener`** (order-service): payment-service의 Outbox → Kafka(`payment-events`)를 구독합니다. PENDING인 주문에 대해 이 이벤트가 도착하면, 그게 진짜 결과이므로 그때 확정/취소를 결정합니다. notification-service와 동일한 Inbox 패턴(`processed_event` 테이블)으로 멱등 처리했습니다. 이미 동기 응답으로 확정/취소가 끝난 주문(PENDING이 아닌 주문)에 대한 이벤트는 그냥 건너뜁니다.
- **`PendingOrderTimeoutSweeper`** (order-service): `PaymentEventListener`도 못 잡는 경우가 있습니다 — Circuit이 계속 OPEN이거나 Bulkhead가 계속 꽉 차서 payment-service가 요청 자체를 한 번도 못 받았다면, Payment 레코드도 Outbox 이벤트도 영원히 안 생겨서 주문이 PENDING에 무한정 머무릅니다. 이 스케줄러가 마지막 안전망으로, 일정 시간(기본 30초) 넘게 PENDING인 주문을 찾아 강제로 취소하고 재고를 복구합니다(`PAYMENT_TIMEOUT`).

이 구조는 실제 결제 게이트웨이(Stripe, 토스페이먼츠 등)들이 흔히 쓰는 패턴과 비슷합니다 — 동기 API 호출로 결제를 트리거하고, 최종 확정은 비동기 이벤트(웹훅)로 받는 방식입니다. 완전히 이벤트 기반(요청도 Kafka로)으로 바꾸는 버전은 `choreography-saga-payment` 브랜치에서 별도로 진행 중입니다.

## 알려진 한계

- 정산 서비스는 아직 구현하지 않았습니다.
- 여러 상품이 섞인 주문의 부분 실패 처리는 다루지 않았습니다 (주문당 상품 1종만 지원, 장바구니를 빼서 이 문제 자체가 발생하지 않음).
- reconciliation-batch는 재동기화 주기(5초) 안에 발생하는 유실은 막지 못합니다 (위 설명 참고).
- 대기열 비활성화(disable) 시, 이미 대기 중이던 티켓들은 자동으로 입장 처리되지 않습니다 (다음 활성화 때까지 대기열에 남아있음).
- `resilience4j.retry.instances.paymentService` 설정을 따로 안 둬서, `@Retry`가 Resilience4j 기본값(최대 시도 3회, 고정 간격 500ms)을 그대로 씁니다. 백오프/지터가 없어서 retry-storm 글에서 다룬 "naive 재시도" 패턴에 더 가깝습니다.
- `PendingOrderTimeoutSweeper`는 타임아웃된 주문을 취소만 하지, payment-service에 재시도를 걸어보진 않습니다. Resilience4j의 재시도가 이미 소진된 뒤라 재시도보다는 포기 후 정리가 맞다고 판단했지만, payment-service가 그사이 복구됐다면 불필요하게 취소되는 주문이 있을 수 있습니다.
