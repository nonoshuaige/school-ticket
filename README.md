# 🎫 校园活动票务系统

> Vue 3 + Spring Boot + MySQL + Redis + RabbitMQ 全栈校园票务平台，聚焦**高并发抢购**与**双 Feed 流推荐**两大核心场景。

## 项目简介

面向毕业晚会、歌手大赛等校内热门活动的票务开售场景，同时提供笔记社区供学生分享活动体验。项目从零构建了完整的订单履约闭环与 Feed 流信息流系统。

## 🎯 核心亮点

### 一、秒杀抢购链路 — Redis Lua 原子操作 + Caffeine 本地售罄 + Stream 异步落库 + 本地消息表可靠回滚

```
用户请求
  │
  ├─ 0. 幂等保护（v3.10）
  │      ├─ 前端生成 UUID 幂等键，携带于请求体
  │      ├─ Redis SET NX EX 300 原子抢占 idempotent:order:{key} = "PENDING"
  │      ├─ 抢占失败 → GET key = orderNo? → 幂等返回已有订单
  │      └─ 抢占失败 → GET key = "PENDING"? → 409 "请求处理中"
  │
  ├─ 1. Caffeine 本地缓存短路（5s TTL）
  │      └─ soldOut=true → 释放幂等键，返回"已售罄"
  │
  ├─ 2. 无锁 selectById 查票档/活动信息
  │
  ├─ 3. Snowflake 预生成订单号（IdUtil.getSnowflakeNextIdStr）
  │
  ├─ 4. purchase.lua 原子执行（单次 Redis 往返）:
  │      ├─ GET soldout → 已售罄? 返回 -1
  │      ├─ GET stock < qty? → SET soldout=1 EX 300 → 返回 -2
  │      ├─ 跨票档检查 → 同活动其他票档有购买? → 返回 -4
  │      ├─ HGET purchase userId → 本票档 > 5张? → 返回 -3
  │      ├─ DECRBY stock qty
  │      ├─ HINCRBY purchase userId qty
  │      └─ XADD stream:orders * orderId userId ticketId qty price expireTime
  │
  ├─ 5. Lua 返回 0 → INSERT MySQL（前端立即可见）
  │      ├─ 成功 → SET idempotent:order:{key} = orderNo
  │      └─ DuplicateKeyException → Stream 先落库 → 幂等返回已有订单
  │
  └─ 6. 正向链路完成
        │
        ▼
  逆向链路（取消/超时关单 / 退款）:
    cancel / expire
      │
      ├─ 1. MySQL 事务内: UPDATE order status + 回补 remaining
      │         └─ INSERT order_event_log (status=0)
      │              → OrderEventLogTask 每 10s 扫描 → rollback.lua → ack status=1
      │
      └─ 2. Caffeine invalidate

    refund
      │
      ├─ 1. INSERT refund (status=0 待退款) → 立即返回
      │
      ├─ 2. RefundTask 每 10s 扫描 refund status=0
      │      └─ processRefund: 回补 remaining + order status=3
      │            + INSERT order_event_log (驱动 Redis 回滚)
      │            + Caffeine invalidate
      │      └─ 成功 → refund status=1 / 失败 → status=2
      │
      └─ 3. OrderEventLogTask 消费 event_log → rollback.lua → ack
```

**关键技术决策：**

| 问题 | 方案 | 效果 |
|------|------|------|
| 库存超卖 | Redis Lua 原子操作（单线程执行，不可打断） | 零超卖 |
| 同活动多票档钻空子 | Lua 内跨票档 HGET 检查，遍历同活动所有 ticketId | 每活动限买一类票档 |
| 每人囤票 | `ticket:purchase:{ticketId}` Hash 记录 userId→qty，单票档上限 5 张 | 杜绝脚本囤票 |
| 售罄后无效请求穿透 Redis | Caffeine 本地缓存 `Cache<Long, Boolean>`，5s TTL，仅存售罄=true | 秒级短路，Redis 压力归零 |
| 订单号全局唯一 | Hutool Snowflake（无中心化依赖） | 预生成，不依赖 DB 自增 |
| 重复提交/网络重试 | Redis SET NX EX 300 原子抢占幂等键 + DuplicateKeyException 兜底 | 同一次请求仅创建一个订单 |
| 缓存/DB 一致性 | 前端订单直插 MySQL + Stream Consumer 异步幂等兜底 | 前端立即可见，双重保障 |
| 库存恢复 | rollback.lua 原子 INCRBY + DEL soldout + 减少购买记录 | 取消/退款秒级回补 |
| 逆向链路可靠性 | 本地消息表 `order_event_log`（与业务在同一事务内 INSERT）→ 定时任务扫描 status=0 → 执行 Lua → 成功 ack status=1，失败重试最多 5 次 | Redis 临时不可用时不影响业务返回，最终一致 |
| 退款异步解耦 | 退款申请写 `refund` 表（refund_id=order_no,status=0）→ RefundTask 消费执行实际退款 → 写 event_log 驱动 Redis 回滚 | 退款请求快速返回，削峰填谷 |
| Caffeine 失效时机 | 取消/超时/退款执行时立即 invalidate，不等 Redis 回滚完成 | 用户感知的售罄状态即时更新 |

**Caffeine 本地售罄缓存：**

```java
Cache<Long, Boolean> cache = Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.SECONDS)   // 5秒自动过期，售罄状态周期性刷新
    .maximumSize(1000)
    .build();
// 仅缓存 soldOut=true，未命中 = 未售罄
// 库存恢复时主动 invalidate
```

**库存预热：** 热卖中活动的票档库存预先写入 Redis `ticket:stock:{ticketId}` Key，TTL=活动结束时间，Lua 执行 DECRBY 无需回源 MySQL。

**活动信息缓存（v3.9 / v3.12 完善）：** 活动列表和详情查询改为 Redis 优先、MySQL 兜底。启动时将所有活跃活动（热卖+预热）预热到 Redis，前端按热卖/预热 Tab 分开展示。v3.12 将 status 改为实时计算字段（基于 saleStartTime/saleEndTime vs 当前时间），消除 DB 状态与时间窗口不同步问题；MySQL 兜底路径补全 saleStartTime ≤ now 条件，确保热卖/预热精确分流。

| Redis Key | 类型 | TTL | 用途 |
|-----------|------|-----|------|
| `idempotent:order:{key}` | String | 300s | 幂等键 → "PENDING" 或 orderNo，防重复提交 |
| `event:pool:hot` | ZSET | saleEndTime | 热卖中活动 ID，score=eventStartTime |
| `event:pool:warmup` | ZSET | max(saleStartTime)+1d | 预热中活动 ID，score=eventStartTime |
| `event:vo:{eventId}` | String (JSON) | saleEndTime | 活动完整信息 + 预计算 minPrice |
| `event:tickets:{eventId}` | String (JSON) | saleEndTime | 票档列表 JSON |

查询路径：页面请求 → Redis ZSET 分页 → HGETALL VO 缓存 → 直接返回（不碰 MySQL）。VO miss / pool 脏数据自动从 MySQL 回填并修复。

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
- **关注联动**：关注时回填被关注者最近 50 条到收件箱 + 检查是否触发大V 标记；取关时清除收件箱该项 + 检查是否摘除大V 标记

**按用户懒加载 + TTL 过期（v3.6）：**

冷启动仅同步 `note:latest` + `note:hottest` 全局池。用户级数据（关注、粉丝、收件箱、点赞集合、我的笔记）在首次访问对应功能时从 MySQL 按需重建并自动设置 TTL。不活跃用户不消耗 Redis 内存。

---

## 🔧 技术栈

| 层 | 技术 | 说明 |
|----|------|------|
| 前端 | Vue 3 (Composition API) · Vite 5 · Pinia · Axios · Vant 4 | 移动端 UI，三 Tab 导航 |
| 后端 | Java 21 · Spring Boot 3.2.5 · Spring Security 6 | Cookie + JWT 双通道无状态认证 |
| ORM | MyBatis-Plus 3.5.7 | 分页插件 + Lambda 查询 |
| 数据库 | MySQL 8.0 | 10 张表，100 用户 / 15 活动 / 200 笔记 |
| 缓存 | Redis 7.4 (Alpine) | ZSET 游标分页 + Lua 脚本 + Stream + Bitmap 布隆 |
| 本地缓存 | Caffeine | 售罄标记，5s TTL |
| 消息队列 | RabbitMQ 3.13 (Alpine) | 4 个 Queue，异步兜底 ZSET/VO 更新 |
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
| POST | /api/v1/order/create | **创建订单（幂等键 + Lua 抢购入口）** |
| POST | /api/v1/order/pay | 模拟支付 |
| POST | /api/v1/order/cancel | 取消订单（写消息表，异步回滚库存） |
| POST | /api/v1/order/refund | 申请退款（写退款表，异步消费执行） |
| GET | /api/v1/note/recommend-feed?cursor=&pageSize= | **推荐流（布隆消重 + 无限滚动）** |
| GET | /api/v1/note/following-feed?cursor=&pageSize= | **关注流（推拉结合，需登录）** |
| GET | /api/v1/note/my-notes?cursor=&pageSize= | 我的笔记 |
| POST | /api/v1/note/create | 发布笔记（触发 fanout） |
| POST | /api/v1/note/{id}/like | 点赞 |
| GET | /api/v1/note/{id}/comment | 评论列表（二级展平） |
| POST | /api/v1/user/{id}/follow | 关注用户（触发回填） |
| DELETE | /api/v1/user/{id}/follow | 取消关注（触发清除） |

---

## 📁 项目结构

```
├── backend/                                # Spring Boot 后端
│   ├── init.sql                            # 建表 + 测试数据（10表 / 100用户 / 200笔记）
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
│           │   ├── consumer/OrderStreamConsumer.java # Stream 异步落库
│           │   ├── cache/SoldOutCache.java         # Caffeine 售罄缓存
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

10 张表：`user` → `event` → `ticket_category` → `order` → `order_event_log` → `refund` → `user_follow` → `user_note` → `user_note_comment` → `note_like`

测试数据：100 用户 · 15 活动 · 44 票档 · 200 笔记 · 295 关注 · 496 点赞

## 许可证

MIT License
