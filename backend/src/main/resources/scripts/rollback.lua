-- 回滚 Lua：取消/退款/超时关单 → 恢复库存 + 清除售罄标记 + 减少用户购买记录
-- KEYS[1]: ticket:stock:{ticketId}
-- KEYS[2]: ticket:soldout:{ticketId}
-- KEYS[3]: event:purchase:{eventId}
-- ARGV[1]: userId
-- ARGV[2]: quantity

-- 恢复库存
redis.call('INCRBY', KEYS[1], ARGV[2])

-- 清除售罄标记（库存回来了）
redis.call('DEL', KEYS[2])

-- 减少用户购买记录
local current = tonumber(redis.call('HGET', KEYS[3], ARGV[1]) or '0')
local qty = tonumber(ARGV[2])
if current > qty then
    redis.call('HINCRBY', KEYS[3], ARGV[1], -qty)
else
    redis.call('HDEL', KEYS[3], ARGV[1])
end

return {0, 'ok'}
