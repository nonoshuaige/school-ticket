-- 抢购 Lua：原子扣库存 + 活动级限购 + 写订单缓存 + 写 Stream
-- KEYS[1]: ticket:stock:{ticketId}
-- KEYS[2]: ticket:soldout:{ticketId}
-- KEYS[3]: event:purchase:{eventId}
-- KEYS[4]: stream:orders
-- KEYS[5]: order:{orderNo}
-- ARGV[1]: orderId
-- ARGV[2]: userId
-- ARGV[3]: ticketId
-- ARGV[4]: quantity
-- ARGV[5]: maxPerUser (5)
-- ARGV[6]: totalPrice
-- ARGV[7]: expireTime (epoch ms)
-- ARGV[8]: orderJson (stub JSON, status=-1 排队中)

-- 售罄检查
local soldout = redis.call('GET', KEYS[2])
if soldout == '1' then
    return {-1, 'sold_out'}
end

-- 库存检查
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
local qty = tonumber(ARGV[4])
if stock < qty then
    redis.call('SET', KEYS[2], '1', 'EX', 300)
    return {-2, 'insufficient_stock'}
end

-- 活动级限购检查
local maxPer = tonumber(ARGV[5])
local current = tonumber(redis.call('HGET', KEYS[3], ARGV[2]) or '0')
if current + qty > maxPer then
    return {-3, 'user_limit_exceeded'}
end

-- 扣库存
redis.call('DECRBY', KEYS[1], qty)

-- 更新活动级购买记录
redis.call('HINCRBY', KEYS[3], ARGV[2], qty)

-- 写订单缓存（排队中状态，Consumer 落库后刷新为 status=0）
redis.call('SET', KEYS[5], ARGV[8], 'EX', 1800)

-- 写 Stream → RabbitMQ → Consumer 落库
redis.call('XADD', KEYS[4], 'MAXLEN', '~', '10000', '*',
    'orderId', ARGV[1],
    'userId', ARGV[2],
    'ticketId', ARGV[3],
    'quantity', ARGV[4],
    'totalPrice', ARGV[6],
    'expireTime', ARGV[7])

return {0, 'ok'}
