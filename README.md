# 🎫 校园活动票务系统

> v4.7 · Vue 3 + Spring Boot + MySQL + Redis + RabbitMQ 全栈校园票务平台，聚焦**高并发抢购**、**双 Feed 流推荐**与**种草笔记**三大核心场景。

## 项目简介

面向毕业晚会、歌手大赛等校内热门活动的票务开售场景，同时提供笔记社区供学生分享活动体验。v4.0 新增种草笔记系统：笔记可关联活动，笔记卡片显示种草徽章，详情页展示活动卡片并一键跳转购票。v4.7 补强 Feed 体验：推荐流仅下拉刷新生成新快照，详情返回恢复原列表与滚动位置，关注状态批量检查避免 N+1。项目从零构建了完整的订单履约闭环与 Feed 流信息流系统。

## 🎯 核心亮点

### 一、秒杀抢购链路 — 四层预检快速失败 + Lua 原子写入 + 排队中状态 + RabbitMQ Confirm + MySQL 库存一致

```
用户请求 POST /order/create { ticketId, quantity }
  │
  ├─ 0. 用户级限流（内存 ConcurrentHashMap，每人 1s/次）→ 防脚本/连点
  │
  ├─ 1. Caffeine 本地售罄短路 + 单飞锁（5s TTL）
  │      ├─ 命中 → 返回"已售罄"
  │      └─ 未命中 → tryLock 单线程查 Redis soldout 标记；锁竞争失败 → 快速返回"请求火爆"
  │
  ├─ 2. Redis 售罄预检（Java 侧 GET soldout，比 Lua 更快失败）
  │
  ├─ 3. Redis 库存预检（GET stock < qty? → 返回售罄）
  │
  ├─ 4. Redis 限购预检（HGET event:purchase:{eventId} userId → current+qty > 5?）
  │        以上三层预检层层削减：售罄概率 > 库存不足 > 单人限购
  │
  ├─ 5. Snowflake 预生成订单号 + 构建排队中订单（status=-1）
  │
  ├─ 6. purchase.lua 原子执行（安全校验 + 写操作，约 12µs）:
  │      ├─ GET soldout → 已售罄? → -1
  │      ├─ GET stock < qty? → SET soldout=1 → -2
  │      ├─ HGET event:purchase:{eventId} userId → current+qty > 5? → -3
  │      ├─ DECRBY stock
  │      ├─ HINCRBY event:purchase:{eventId} userId qty
  │      ├─ SET order:{orderNo} = JSON(status=-1 排队中), EX 1800  ← Lua 内写订单
  │      └─ XADD stream:orders (唯一写路径)
  │
  ├─ 7. 返回 stub Order（status=-1，前端展示"排队中"蓝色状态）
  │      │
  │      ▼
  │  StreamToRabbitMQBridge (阻塞队列, v4.6)
  │      │  Reader 线程 XREAD BLOCK → LinkedBlockingQueue
  │      │  Worker×4 take() → 同时投递 order.create / order.delay → 两条消息均收到 publisher confirm ack 后 XACK
  │      │
  │      │  PEL 兜底 (每 5s): XPENDING → 卡住 >5s → 重投 RabbitMQ → confirm ack 后 XACK
  │      │
  │      ├─ order.create.queue → OrderCreateConsumer 唯一落库
  │      └─ order.delay.queue(TTL=15min) → order.close.queue 超时关单
  │
  │      ▼
  │  OrderCloseConsumer @RabbitListener
  │      │  CAS 条件更新：status=0 && expire_time<=now 才能关单
  │      │  写 order_event_log 驱动 Redis 回滚，重复消息幂等跳过
  │      │
  │      ▼
  │  OrderCreateConsumer @RabbitListener
  │      │  INSERT order + 原子扣减 MySQL ticket_category.remaining_quantity (同事务)
  │      │  刷新 Redis: status=-1(排队中) → status=0(待支付) + 真实 createTime
  │      │  DuplicateKeyException → 幂等跳过
  │      │  异常 → 抛回 RabbitMQ 重试 / DXL
  │      │
  │      ▼ 查询 GET /order/{orderNo} 或 /order/list → Redis 优先，全程不穿透 DB
  
  逆向链路（取消/超时关单 / 退款）:
    cancel / expire
      │
      ├─ MySQL 事务内: CAS UPDATE order status + 原子回补 remaining
      │         └─ INSERT order_event_log (status=0，任务处理前 CAS 抢占为 status=9)
      │              → OrderEventLogTask 每 10s → rollback.lua → ack
      └─ Caffeine 不主动 invalidate，5s TTL 自然过期

    refund
      │
      ├─ INSERT refund (status=0) → 立即返回
      ├─ RefundTask 每 10s 消费 → 回补 + UPDATE status=3
      │         └─ INSERT order_event_log → 驱动 Redis 回滚（重复退款事件幂等跳过）
      └─ Caffeine 不主动 invalidate，5s TTL 自然过期
```

**关键技术决策：**

| 问题 | 方案 | 效果 |
|------|------|------|
| 库存超卖 | Redis Lua 原子操作（单线程执行，不可打断） | 零超卖 |
| 同活动多票档钻空子 | Lua 内 event:purchase:{eventId} Hash 活动级累计，所有票档合计 ≤5 张 | 每活动最多 5 张，可跨票档组合 |
| 每人囤票 | `event:purchase:{eventId}` Hash 记录 userId→qty，活动级上限 5 张 | 杜绝脚本囤票 |
| 售罄后无效请求穿透 Redis | Caffeine 本地缓存 `Cache<Long, Boolean>`，5s TTL，仅存售罄=true | 秒级短路，Redis 压力归零 |
| 订单号全局唯一 | Hutool Snowflake（无中心化依赖） | 预生成，不依赖 DB 自增 |
| 重复提交/脚本攻击 | 内存限流（ConcurrentHashMap，每人 1s/次）+ 前端按钮 1s 防连点（v4.5） | 双重保护，不占 Redis 内存 |
| Java 侧预检快速失败 | 售罄→库存→限购 三层 Redis 读（概率递减），无效请求不进 Lua（v4.5） | 减少无效 Lua 调用 |
| Lua 脚本原子性 | 安全校验 + DECRBY + HINCRBY + SET 订单缓存 + XADD stream 全部在 Lua 内完成（v4.5） | 订单写入无间隙，状态一致 |
| 订单排队状态 | Lua 写 status=-1（排队中）→ Consumer 落库刷 status=0（待支付）→ 前端区分展示（v4.5） | 查询全程走 Redis，零 DB 穿透 |
| Caffeine 击穿 | 单飞锁：per-ticketId tryLock，抢到锁才回查 Redis，锁竞争失败快速返回（v4.6） | 同类票档同一时刻仅一次 Redis 查询，不放大线程等待 |
| Stream 不可靠（内存级） | Stream→RabbitMQ 桥接线程 + PEL 兜底重投，同时投递成单队列与延时关单队列（v4.6） | 磁盘级可靠，消息不因 Redis 重启丢失 |
| MQ 投递确认窗口 | RabbitMQ `publisher-confirm-type=correlated`，成单与延时两条消息均 confirm ack 后才 XACK Redis Stream（v4.7） | 避免 publish 未被 broker 确认时提前 ack Stream |
| MySQL 库存口径 | OrderCreateConsumer 插入订单与 `remaining_quantity -= qty` 同事务；取消/退款/超时使用原子 `remaining_quantity += qty`（v4.7） | Redis 热库存与 MySQL 最终库存对称一致 |
| 支付/关单状态冲突 | 状态机 + CAS 条件更新（status 前置条件 + expire_time 条件） | 支付、取消、超时、退款、核销并发时仅一个流转成功 |
| 活动缓存击穿 | 逻辑过期 + 互斥锁（SET NX），物理 TTL=逻辑+30min 缓冲，过期返回旧值异步重建（v4.1） | 大量并发不再穿透 DB |
| Pool 重建空窗期 | 临时 ZSET 构建 + RENAME 原子替换，避免先删后建（v4.5） | 重建期间查询不返回空列表 |
| 库存恢复 | rollback.lua 原子 INCRBY + DEL soldout + 减少购买记录 | 取消/退款秒级回补 |
| 逆向链路可靠性 | 本地消息表 `order_event_log`（与业务在同一事务内 INSERT）→ 定时任务 CAS 抢占 `0→9` → 执行 Lua → 成功 ack，失败释放或重试最多 5 次 | Redis 临时不可用时不影响返回，最终一致且避免重复回滚 |
| 退款异步解耦 | 退款申请写 `refund` 表（refund_id=order_no,status=0）→ RefundTask CAS 抢占 `0→9` → 写 event_log 驱动 Redis 回滚 | 退款请求快速返回，重复消费幂等跳过 |
| Caffeine 失效时机 | 不主动 invalidate，依赖 5s TTL 自然过期 + OrderEventLogTask 清除 Redis soldout 标记后缓存冷却 | 避免假售罄循环 |

**Caffeine 本地售罄缓存（v4.6 单飞锁快速失败）：**

```java
Cache<Long, Boolean> cache = Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.SECONDS)   // 5秒自动过期
    .maximumSize(1000)
    .build();
// 仅缓存 soldOut=true，未命中 = 未售罄
// checkSoldOut(): Caffeine 命中 → 直接返回；未命中 → tryLock 单线程查 Redis
// tryLock 失败 → 直接快速返回"请求过于火爆"，不自旋等待、不进入 Lua
// 取消/退款不再主动 invalidate，依赖 TTL 自然过期
// 假售罄修复：Redis soldout 标记由 OrderEventLogTask 清除，缓存过期后下次查询正确状态
```

**库存预热与一致性：** 热卖中活动的票档库存预先写入 Redis `ticket:stock:{ticketId}` Key，TTL=活动结束时间，Lua 执行 DECRBY 无需回源 MySQL；成单消费者异步落库时同步原子扣减 MySQL `remaining_quantity`，取消/退款/超时再原子回补，保持热库存与最终库存口径对称。

**活动信息缓存（v3.9 / v3.12 / v4.1 逻辑过期）：** 活动列表和详情查询改为 Redis 优先、MySQL 兜底。v4.1 引入逻辑过期 + 互斥锁防击穿：缓存 value 包裹 `{"expireAt":...,"data":{...}}`，物理 TTL=逻辑过期+30min 缓冲。逻辑过期时返回旧值并异步重建，大量并发不再穿透 DB。v3.12 将 status 改为实时计算字段。

| Redis Key | 类型 | 物理TTL | 逻辑过期 | 用途 |
|-----------|------|---------|---------|------|
| `order:{orderNo}` | String | 1800s | — | 订单 JSON 缓存（Lua 写入 status=-1 排队中 → Consumer 落库后刷新 status=0 可见） |
| `user:orders:{userId}` | List | 1800s | — | 用户最近 10 条订单号，LPUSH + LTRIM 维护，首页列表 Redis 直达 |
| `event:pool:hot` | ZSET | max(saleEndTime)+30min | 每5min 重建 | 热卖中活动 ID，score=eventStartTime |
| `event:pool:warmup` | ZSET | max(saleStartTime)+1d+30min | 每5min 重建 | 预热中活动 ID |
| `event:vo:{eventId}` | String (JSON) | logicExpire+30min | saleEndTime(热) / saleStartTime(预) | 活动完整信息 + minPrice |
| `event:tickets:{eventId}` | String (JSON) | logicExpire+30min | 同上 | 票档列表 JSON |
| `mutex:event:pool:{status}` | String | 10s | — | Pool 互斥锁，防并发重建 |

查询路径：Pool key 存在且未逻辑过期 → 直接返回。逻辑过期 → 抢锁异步重建 + 返回旧值。Pool 缺失 → 抢锁同步重建。

---

### 二、双 Feed 流 — 推荐流（布隆消重）+ 关注流（推拉结合）

```
┌─ 推荐流（需 userId 消重）─────────────────────┐
│                                              │
│  note:latest (ZSET)  ── 全站最新候选池 (1d)   │
│       │                                      │
│       ▼  shuffle → user:bloom:seen:{userId}  │
│       │   Bloom 去重 → 截取 200              │
│       ▼                                      │
│  feed:recommend:{userId} (List, 30min)       │
│       │  session 级私有快照，未登录 → userId=0 │
│       ▼                                      │
│  LPOP pageSize 条 → VO 缓存批量读 → 响应      │
│                                              │
│  热点延迟: 23-30ms（含 pipeline 批量优化）     │
└──────────────────────────────────────────────┘

┌─ 关注流（需登录，推拉结合 v3.11）─────────────┐
│                                              │
│  发布笔记时分支判断:                            │
│    fanCount < 1000?                           │
│    ├─ YES → 推模式: fanout 到粉丝收件箱         │
│    │    遍历作者粉丝 → feed:following:{fanId}   │
│    │    ZADD 写入每个粉丝收件箱 → 裁剪至 800     │
│    │                                          │
│    └─ NO  → 拉模式: 标记 bigv:ids，不 fanout   │
│             仅写 note:mine:{authorId}          │
│       │                                      │
│       ▼                                      │
│  读取时合并两源:                                │
│    ├─ 收件箱 feed:following:{userId}（推内容）  │
│    ├─ 大V mine note:mine:{bigVId}（拉内容）     │
│    ├─ 按时间戳 merge → 游标分页                 │
│       │                                      │
│       ▼                                      │
│  VO 缓存批量读 → 响应                          │
│                                              │
│  新粉关注 → 从 author mine 回填 50 条          │
│  取关    → ZREM 该作者全部笔记 + 摘除大V 标记   │
└──────────────────────────────────────────────┘
```

**推荐流链路（布隆过滤器 + Pipeline 批量优化）：**

| Redis Key | 类型 | TTL | 用途 |
|-----------|------|-----|------|
| `note:latest` | ZSET | 1天 | 全站最新候选池，过期从 DB 重建 |
| `note:hottest` | ZSET | 1天 | 热度排行（点赞数） |
| `user:bloom:seen:{userId}` | String (Bitmap) | 7天 | 布隆过滤器，~12KB/人，曝光消重 |
| `feed:recommend:{userId}` | List | 30分钟 | Session 私有快照队列 |
| `note:vo:{noteId}` | Hash | 7天 | 笔记全量 VO 缓存（按需写入） |
| `note:like:count:{noteId}` | String | 7天 | Redis 先行点赞计数器 |
| `user:likes:{userId}` | Set | 3天 | 用户点赞集合，O(1) SISMEMBER 判赞 |

**Pipeline 批量优化（v3.5）：**

推荐流首次加载原需 ~350 次 Redis 往返，优化后降至 ~15 次：

| 优化点 | 之前 | 之后 | 节省 |
|--------|------|------|------|
| 布隆检查 200 候选 | 200 次 GETBIT | 1 次 pipeline | 199 次 RTT |
| 布隆标记 | 80 次 SETBIT | 1 次 pipeline | 79 次 RTT |
| VO 缓存批量读 | 10 次 HGETALL | 1 次 pipeline | 9 次 RTT |
| isLiked 批量判赞 | 10 次 SISMEMBER | 1 次 pipeline | 9 次 RTT |
| 快照弹出 | 10 次 LPOP | 1 次 pipeline | 9 次 RTT |
| 空白布隆短路 | 无 | EXISTS 跳过 | 节省 130ms |

> 推荐流冷启动 ~420ms（含 Lettuce 首连接 ~220ms），热路径 **23-30ms**。

**Feed 体验与关注状态优化（v4.7）：**

- 推荐流不再在每次进入 `/notes` 时强制刷新：仅下拉刷新生成新快照，继续下滑复用 `feed:recommend:{userId}` 私有队列 `LPOP pageSize`。
- 前端使用 `sessionStorage` 保存当前 Feed Tab、列表、游标和滚动位置，进入笔记详情后返回可恢复原列表和原滚动位置。
- 滚动接近底部自动触发 `loadMore`，同时保留"加载更多"按钮作为显式兜底。
- 新增 `POST /api/v1/user/follow/check/batch` 批量关注检查接口，替代 Feed 卡片逐条 `checkFollowing`，消除前端 N+1 请求。
- 批量接口使用 `/check/batch` 多段路径，避免被 `POST /user/follow/{userId}` 误匹配。

**关注流链路（v3.11 推拉结合）：**

| Redis Key | 类型 | TTL | 用途 |
|-----------|------|-----|------|
| `feed:following:{userId}` | ZSET | 3天 | 粉丝收件箱，仅普通用户（<1000粉）发布时 fanout 写入，最多 800 条 |
| `bigv:ids` | Set | 持久 | 大V 用户 ID 集合，粉丝数 ≥ 阈值时自动加入 |
| `user:follow:{userId}` | ZSET | 3天 | 关注作者列表 |
| `user:fans:{userId}` | ZSET | 3天 | 粉丝列表（fanout 时查询） |
| `note:mine:{userId}` | ZSET | 3天 | 作者发布的笔记索引（大V 拉模式 + 新粉回填数据源） |

**推拉结合关键机制：**

- **发帖分支**（`feed.big-v-threshold: 1000`）：粉丝 < 1000 → 推，fanout 到粉丝收件箱；粉丝 ≥ 1000 → 拉，仅写 `note:mine` + 标记 `bigv:ids`
- **读时合并**：收件箱（普通关注者推内容）+ 大V `note:mine` 拉取 → 按时间戳降序合并 → 游标分页
- **关注联动**：关注/取关在主流程同步维护收件箱（关注回填最近 50 条，取关清理该作者笔记）+ RabbitMQ 异步兜底，避免 MQ 未启动时关注关系已生效但关注流缺旧笔记；同时检查是否触发/摘除大V 标记

**按用户懒加载 + TTL 过期（v3.6）：**

冷启动仅同步 `note:latest` + `note:hottest` 全局池。用户级数据（关注、粉丝、收件箱、点赞集合、我的笔记）在首次访问对应功能时从 MySQL 按需重建并自动设置 TTL。不活跃用户不消耗 Redis 内存。

---

### 三、种草笔记系统 — 笔记关联活动 + 种草徽章 + 活动卡片跳转（v4.0）

```
┌─ 种草笔记链路 ─────────────────────────────────┐
│                                                │
│  发布笔记 POST /api/v1/note/create              │
│    { content, eventIds: [1, 2] }               │
│       │                                        │
│       ├─ INSERT user_note                       │
│       ├─ INSERT note_event (noteId, eventId)    │
│       └─ cacheNoteEvents → Redis                │
│              │                                  │
│              ▼                                  │
│  Feed 流加载时（推荐/关注/我的笔记）:              │
│    ├─ assembleNoteVOsWithRepair                 │
│    │    ├─ pipeline GET  note:events:{noteId}   │
│    │    │   miss → MySQL note_event → 回填      │
│    │    └─ pipeline GET  event:vo:{eventId}     │
│    │        miss → MySQL event → 回填            │
│    ▼                                            │
│  VO 含 eventIds + events[]                      │
│    ├─ eventIds.length > 0?                      │
│    │   └─ YES → 卡片显示 🛍️种草 徽章             │
│    └─ 笔记详情页:                                │
│        └─ 种草好物区域 → EventCard 双列 Grid     │
│           └─ 点击 → /event/:id 购票              │
└────────────────────────────────────────────────┘
```

| Redis Key | 类型 | TTL | 用途 |
|-----------|------|-----|------|
| `note:events:{noteId}` | String | 7天 | 逗号分隔的关联 eventId 列表（空串=无关联），按需懒加载 |
| `event:vo:{eventId}` | String (JSON) | saleEndTime | 活动摘要（title, minPrice, venue, time...），复用活动缓存 |

**设计要点：**
- `note_event` 多对多表以代理自增主键 + 唯一约束解耦笔记与活动
- 种草数据按需懒加载：不活跃笔记不占 Redis 内存，与 VO 缓存模式一致
- v4.7 修复空活动关联缓存穿透：`note:events:{noteId}` 空串表示"已缓存且无关联活动"，读取时同样视为命中，不再反复查 MySQL
- 活动摘要复用现有 `event:vo:{eventId}` JSON 缓存，与活动列表共享
- 10 条测试种草笔记，内容与关联活动精确对应

---

## 🔧 技术栈

| 层 | 技术 | 说明 |
|----|------|------|
| 前端 | Vue 3 (Composition API) · Vite 5 · Pinia · Axios · Vant 4 | 移动端 UI，三 Tab 导航 |
| 后端 | Java 21 · Spring Boot 3.2.5 · Spring Security 6 | Cookie + JWT 双通道无状态认证 |
| ORM | MyBatis-Plus 3.5.7 | 分页插件 + Lambda 查询 |
| 数据库 | MySQL 8.0 | 11 张表，100 用户 / 15 活动 / 200 笔记 / 10 种草关联 |
| 缓存 | Redis 7.4 (Alpine) | ZSET 游标分页 + Lua 脚本 + Stream + Bitmap 布隆 |
| 本地缓存 | Caffeine | 售罄标记，5s TTL |
| 消息队列 | RabbitMQ 3.13 (Alpine) | 8 个 Queue（笔记/关注 + 订单创建/延时关单/死信），异步兜底 + 可靠落库 |
| 容器 | Docker Desktop | Redis + RabbitMQ |

---

## 🚀 快速开始

### 环境要求

- JDK 21 · Maven 3.9+ · MySQL 8.0 · Node.js 20+ · Docker Desktop

### 1. 启动基础设施

```bash
# 启动 MySQL
taskkill //f //im mysqld.exe 2>/dev/null
"D:/JavaDevelop/mysql-8.0.28-winx64/bin/mysqld.exe" --defaults-file="D:/JavaDevelop/mysql-8.0.28-winx64/my.ini" &

# 启动 Redis + RabbitMQ 容器
docker start redis
docker start rabbitmq
```

### 2. 初始化数据库 & Redis 同步

```bash
mysql -u root -p123456 --default-character-set=utf8mb4 -D school_ticket < backend/init.sql

# 启动后端后，同步全局池
curl -X POST http://localhost:8080/api/v1/note/sync-to-redis

# 登录测试账号并同步关注关系
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","password":"123456"}' \
  -c cookies.txt
curl -X POST http://localhost:8080/api/v1/user/follow/sync-to-redis -b cookies.txt
```

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run
# → http://localhost:8080，API 前缀 /api/v1
```

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

### 5. 服务总览

| 服务 | 地址 | 端口 |
|------|------|------|
| 前端页面 | http://localhost:5173 | 5173 |
| 后端 API | http://localhost:8080 | 8080 |
| MySQL | localhost | 3306 |
| Redis | localhost | 6379 |
| RabbitMQ AMQP | localhost | 5672 |
| RabbitMQ 管理界面 | http://localhost:15672 | 15672 (guest/guest) |

### 测试账号

| 手机号 | 密码 | 昵称 |
|--------|------|------|
| 13800138000 | 123456 | 测试用户 (user_id=1) |

---

## 📡 API 一览

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | /api/v1/auth/register | 注册 |
| POST | /api/v1/auth/login | 登录（JWT 写入 Cookie + 响应体） |
| GET | /api/v1/event/list?status=&page=&pageSize= | 活动列表（status=1热卖/0预热，Redis优先，含最低票价） |
| GET | /api/v1/event/{id} | 活动详情 + 票档 |
| POST | /api/v1/order/create | **创建订单（Lua 抢购入口 + Redis 订单缓存）** |
| POST | /api/v1/order/pay | 模拟支付 |
| POST | /api/v1/order/cancel | 取消订单（写消息表，异步回滚库存） |
| POST | /api/v1/order/refund | 申请退款（写退款表，异步消费执行） |
| GET | /api/v1/note/recommend-feed?cursor=&pageSize= | **推荐流（布隆消重 + 无限滚动）** |
| GET | /api/v1/note/following-feed?cursor=&pageSize= | **关注流（推拉结合，需登录）** |
| GET | /api/v1/note/my-notes?cursor=&pageSize= | 我的笔记 |
| POST | /api/v1/note/create | 发布笔记（支持 eventIds 关联活动种草） |
| POST | /api/v1/note/{id}/like | 点赞 |
| GET | /api/v1/note/{id}/comment | 评论列表（二级展平） |
| POST | /api/v1/user/follow/{id} | 关注用户（同步回填收件箱 + MQ 兜底） |
| DELETE | /api/v1/user/follow/{id} | 取消关注（同步清理收件箱 + MQ 兜底） |
| POST | /api/v1/user/follow/check/batch | 批量检查当前用户是否关注作者（Feed 卡片用） |

---

## 📁 项目结构

```
├── backend/                                # Spring Boot 后端
│   ├── init.sql                            # 建表 + 测试数据（11表 / 100用户 / 200笔记 / 10种草关联）
│   └── src/main/
│       ├── resources/scripts/
│       │   ├── purchase.lua                # 抢购 Lua：原子扣库存 + 限购 + Stream
│       │   └── rollback.lua                # 回滚 Lua：恢复库存 + 清除售罄
│       └── java/com/schoolticket/
│           ├── order/
│           │   ├── controller/OrderController.java
│           │   ├── entity/OrderEventLog.java       # 本地消息表实体
│           │   ├── entity/Refund.java              # 退款表实体
│           │   ├── mapper/OrderEventLogMapper.java
│           │   ├── mapper/RefundMapper.java
│           │   ├── service/OrderService.java       # 抢购编排 + 写消息表/退款表
│           │   ├── service/OrderLuaService.java    # Lua 执行 + 库存预热
│           │   ├── consumer/StreamToRabbitMQBridge.java # Stream→RabbitMQ 桥接 + PEL 兜底
│           │   ├── consumer/OrderCreateConsumer.java    # RabbitMQ 消费者，唯一落库路径
│           │   ├── consumer/OrderCloseConsumer.java     # RabbitMQ 延时关单消费者
│           │   ├── cache/SoldOutCache.java         # Caffeine 售罄缓存 + 单飞锁
│           │   ├── cache/RateLimiter.java          # 用户级请求限流（每人 1s/次）
│           │   ├── task/OrderTimeoutTask.java      # 超时关单扫描
│           │   ├── task/OrderEventLogTask.java     # 本地消息表定时处理
│           │   └── task/RefundTask.java            # 退款表消费任务
│           ├── note/
│           │   ├── controller/NoteController.java
│           │   ├── service/NoteService.java        # Feed 流业务编排
│           │   ├── service/RedisNoteRankingService.java # ZSET 排行 + fanout
│           │   ├── service/BloomFilterService.java # Bitmap 布隆过滤器
│           │   └── service/NoteCommentService.java # 二级展平评论
│           └── dto/                        # CursorPage, OrderVO, CommentVO 等
│
├── frontend/                               # Vue 3 前端
│   └── src/
│       ├── views/
│       │   ├── EventList.vue               # 活动列表（热卖/预热 Tab 切换 + 双列网格）
│       │   ├── Notes.vue                   # 双 Feed 切换（推荐/关注）
│       │   └── NoteDetail.vue              # 笔记详情 + 二级评论
│       ├── api/                            # auth / event / order / note / user
│       └── stores/                         # Pinia: user / order
│
└── 开发文档.md                              # 完整开发说明书
```

---

## 🗄️ 数据库

11 张表：`user` → `event` → `ticket_category` → `order` → `order_event_log` → `refund` → `user_follow` → `user_note` → `user_note_comment` → `note_like` → `note_event`

测试数据：100 用户 · 15 活动 · 44 票档 · 200 笔记 · 295 关注 · 496 点赞 · 10 种草关联

## 许可证

MIT License
