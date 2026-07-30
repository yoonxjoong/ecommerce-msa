-- KEYS[1] = 재고 카운터 키 (예: "stock:product:1")
-- KEYS[2] = 멱등성 키 (예: "idempotency:reserve:order-1234")
-- ARGV[1] = 차감할 수량
-- return -1 = 키 없음, 0 = 재고 부족, 1 = 차감 성공

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 1
end

local stock = tonumber(redis.call('GET', KEYS[1]))
local qty = tonumber(ARGV[1])

if stock == nil then
    return -1
end

if stock < qty then
    return 0
end

redis.call('DECRBY', KEYS[1], qty)
redis.call('SET', KEYS[2], '1', 'EX', 86400)
return 1
