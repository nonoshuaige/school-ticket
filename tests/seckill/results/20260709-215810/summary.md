# 抢购链路测试结果

执行时间：2026-07-09 21:58:10
结果目录：`tests/seckill/results/20260709-215810`

本次测试范围：非支付抢购链路。未执行支付、退款、支付回调相关测试。

## 环境重置

日志：`00-reset.log`

已执行：
- 重建 `school_ticket` 测试库
- `Redis FLUSHALL`
- 重建 `stream:orders` / `order-consumers`
- 清空 RabbitMQ 相关队列
- 重启后端并触发活动库存预热

结果：通过

## TC-01 活动级跨票档限购

日志：`01-limit.log`

票档：`21,22,23`，活动：`event_id=8`

步骤与结果：

| 步骤 | 请求 | 结果 |
| --- | --- | --- |
| 1 | `ticket=21, quantity=2` | 成功 1 单 |
| 2 | `ticket=22, quantity=3` | 成功 1 单 |
| 3 | `ticket=23, quantity=1` | 被拒，提示已购 5 张 |

结论：通过。活动级限购最多 5 张有效，跨票档不能绕过。

## TC-02 高并发下单

日志：`02-load.log`

参数：
- `ticket_id=21`
- `users=100`
- `requests=100`
- `concurrency=100`

结果：
- 成功订单：99
- 唯一订单：99
- 失败：1 次用户级限流
- p50：441.58ms
- p95：529.48ms
- p99：534.2ms

结论：通过。单飞锁优化后没有出现大面积“请求过于火爆”，高并发体验明显改善。

## TC-03 售罄与售罄后过滤

日志：`03-soldout.log`

票档：`ticket_id=21`，库存 200。

结果：

| 阶段 | 成功订单 | 主要失败原因 |
| --- | ---: | --- |
| round 1 | 99 | 1 次活动限购 |
| round 2 | 0 | 100 次售罄 |
| 售罄后探测 | 0 | 100 次用户级限流 |

结合前置限购与高并发测试，`ticket_id=21` 被打满：
- 限购测试占用 2 张
- 高并发下单占用 99 张
- 售罄 round 1 占用 99 张
- 合计 200 张

结论：通过。没有超卖，售罄后没有新增成功订单。

说明：售罄后探测紧接上一轮执行，因此被用户级 1 秒限流拦截；上一轮已经明确返回 100 次售罄。

## TC-04 RabbitMQ 短暂不可用

日志：`04-mq-failover.log`

票档：`ticket_id=24`，活动：`event_id=9`

步骤：
1. 停止 RabbitMQ
2. 下单 10 次
3. 查看 Redis Stream PEL
4. 恢复 RabbitMQ
5. 再次查看 PEL

结果：
- RabbitMQ 停止期间下单成功：10
- RabbitMQ 停止期间 PEL：10
- RabbitMQ 恢复后 PEL：0

结论：通过。Redis Stream + PEL 补投生效，RabbitMQ 恢复后消息被确认。

## TC-05 主动取消回滚

日志：`05-cancel-rollback.log`

票档：`ticket_id=24`

结果：
- 创建订单成功：1
- 取消订单成功：1
- 等待 12 秒后由 `OrderEventLogTask` 执行 Redis 回滚

结论：通过。后续 verify 显示本地消息表无 pending，库存对账正常。

## 自动对账

### ticket_id=21 / event_id=8

日志：`06-verify-ticket21.log`

全部 PASS：

| 检查项 | 结果 |
| --- | --- |
| MySQL 库存恒等式 | PASS，total=200, remaining=0, occupied=200 |
| Redis 库存与 MySQL 对齐 | PASS，redis=0 |
| 活动级限购 | PASS，maxEffectiveQtyPerUser=5 |
| Redis Stream PEL | PASS，pending=0 |
| RabbitMQ order.create | PASS，messages=0, unacked=0 |
| RabbitMQ dead queue | PASS，messages=0 |
| order_event_log pending | PASS |
| refund pending | PASS |

### ticket_id=24 / event_id=9

日志：`07-verify-ticket24.log`

全部 PASS：

| 检查项 | 结果 |
| --- | --- |
| MySQL 库存恒等式 | PASS，total=500, remaining=490, occupied=10 |
| Redis 库存与 MySQL 对齐 | PASS，redis=490 |
| 活动级限购 | PASS，maxEffectiveQtyPerUser=1 |
| Redis Stream PEL | PASS，pending=0 |
| RabbitMQ order.create | PASS，messages=0, unacked=0 |
| RabbitMQ dead queue | PASS，messages=0 |
| order_event_log pending | PASS |
| refund pending | PASS |

## 总结

本次非支付抢购链路测试通过。

已验证：
- 单飞锁优化后，高并发未售罄场景不再大面积误杀请求
- Redis Lua 扣库存没有超卖
- 活动级跨票档限购有效
- 售罄后无新增成功订单
- RabbitMQ 短暂不可用时，Stream PEL 可保留并补投
- 主动取消后，MySQL 与 Redis 库存最终一致
- RabbitMQ 成单队列、死信队列、Redis PEL 均无异常积压

本轮未覆盖：
- 支付接口
- 支付与关单并发
- 退款幂等
- 真实支付网关回调
