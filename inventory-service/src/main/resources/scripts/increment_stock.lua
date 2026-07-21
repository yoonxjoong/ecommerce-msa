-- KEYS[1] = 재고 카운터 키
-- ARGV[1] = 복구할 수량 (Saga 보상 트랜잭션에서 사용)

redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
return 1
