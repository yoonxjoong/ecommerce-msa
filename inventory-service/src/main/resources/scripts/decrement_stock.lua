-- KEYS[1] = 재고 카운터 키 (예: "stock:product:1")
-- ARGV[1] = 차감할 수량
-- return -1 = 키 없음, 0 = 재고 부족, 1 = 차감 성공

local stock = tonumber(redis.call('GET', KEYS[1]))
local qty = tonumber(ARGV[1])

if stock == nil then
    return -1
end

if stock < qty then
    return 0
end

redis.call('DECRBY', KEYS[1], qty)
return 1
