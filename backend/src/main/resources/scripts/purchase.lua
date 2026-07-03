-- 抢购 Lua：原子扣库存 + 活动级限购 + 写 Stream
-- KEYS[1]: ticket:stock:{ticketId}
-- KEYS[2]: ticket:soldout:{ticketId}
-- KEYS[3]: event:purchase:{eventId}
-- KEYS[4]: stream:orders
-- KEYS[5]: dedup:order:{userId}:{ticketId}
-- ARGV[1]: orderId
-- ARGV[2]: userId
-- ARGV[3]: ticketId
-- ARGV[4]: quantity
-- ARGV[5]: maxPerUser (5)
-- ARGV[6]: totalStock (fallback)
-- ARGV[7]: totalPrice
-- ARGV[8]: expireTime (epoch ms)

-- 防重：同一用户同一票档 1s 内不允许重复提交（防脚本/连点）
local dedup = redis.call('SET', KEYS[5], '1', 'EX', 1, 'NX')
if not dedup then
    return {-4, 'duplicate_request'}
end

local soldout = redis.call('GET', KEYS[2])
if soldout == '1' then
    return {-1, 'sold_out'}
end

local stock = tonumber(redis.call('GET', KEYS[1]) or ARGV[6])
local qty = tonumber(ARGV[4])
if stock < qty then
    redis.call('SET', KEYS[2], '1', 'EX', 300)
    return {-2, 'insufficient_stock'}
end

local maxPer = tonumber(ARGV[5])
local userId = ARGV[2]
local ticketId = ARGV[3]

-- 活动级限购检查：同一 event 下所有票档合计最多 maxPer 张
local current = tonumber(redis.call('HGET', KEYS[3], userId) or '0')
if current + qty > maxPer then
    return {-3, 'user_limit_exceeded'}
end

-- 扣库存
redis.call('DECRBY', KEYS[1], qty)

-- 更新用户在活动下的累计购买记录
redis.call('HINCRBY', KEYS[3], userId, qty)

-- 写 Stream
redis.call('XADD', KEYS[4], 'MAXLEN', '~', '10000', '*',
    'orderId', ARGV[1],
    'userId', userId,
    'ticketId', ticketId,
    'quantity', ARGV[4],
    'totalPrice', ARGV[7],
    'expireTime', ARGV[8])

return {0, 'ok'}
