# 🎫 校园活动票务系统

> Vue 3 + Spring Boot + MySQL + Redis + RabbitMQ 全栈校园票务平台，支持活动售票、订单履约、笔记社区。

## 功能概览

| 模块 | 说明 |
|------|------|
| 🔐 用户 | 手机号注册/登录（JWT + Cookie）、关注/取关、粉丝统计、游标分页 |
| 🎪 活动 | 热卖中/预热中分组、双列网格、真实最低票价、分页浏览 |
| 🎟️ 购票 | 选票档 → 确认下单（名额扣减）→ 模拟支付 → 倒计时关单 |
| 📦 订单 | 待支付→已支付→已核销 / 已取消 / 已退款，五态流转 |
| 📝 笔记 | 双列小红书风格、推荐流（布隆消重）+ 关注流（推模式收件箱）双 Feed、无限滚动、发布笔记、点赞/取消、关注/取关、笔记详情、二级展平评论、我的笔记独立入口 |
| 📱 导航 | 底部三 Tab：购票、笔记、我的 |

## 技术栈

**后端**：Java 21 · Spring Boot 3.2.5 · Spring Security · MyBatis-Plus 3.5.7 · MySQL 8.0 · Redis 7.4 (ZSET) · RabbitMQ 3.13 · JWT

**前端**：Vue 3 (Composition API) · Vite 5 · Pinia · Axios · Vant 4

## 快速开始

### 环境要求

- JDK 21 · Maven 3.9+ · MySQL 8.0 · Node.js 20+ · Docker Desktop

### 1. 启动基础设施

```bash
# 启动 MySQL（管理员 PowerShell）
Start-Process -FilePath "D:\JavaDevelop\mysql-8.0.28-winx64\bin\mysqld.exe" -ArgumentList "--defaults-file=`"D:\JavaDevelop\mysql-8.0.28-winx64\my.ini`"" -Verb RunAs -WindowStyle Hidden

# 启动 Redis 容器
docker start redis

# 启动 RabbitMQ 容器
docker start rabbitmq
```

### 2. 初始化数据库并同步 Redis

```bash
# 导入表结构和测试数据
mysql -u root -p --default-character-set=utf8mb4 -D school_ticket < backend/init.sql

# 启动后端后，同步全局池到 Redis（冷启动）
curl -X POST http://localhost:8080/api/v1/note/sync-to-redis

# 用户登录后，按需同步该用户的关注关系（其他用户数据由懒加载自动处理）
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
# 运行在 http://localhost:8080，API 前缀 /api/v1
```

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
# 运行在 http://localhost:5173
```

### 5. 测试账号

| 手机号 | 密码 |
|--------|------|
| 13800138000 | 123456 |

## 项目结构

```
├── backend/                          # Spring Boot 后端
│   ├── init.sql                      # 建表 + 测试数据（8表 / 100用户 / 15活动 / 200笔记）
│   ├── gen_data.js                   # 测试数据生成脚本
│   └── src/main/java/com/schoolticket/
│       ├── auth/                     # 认证：登录/注册/JWT
│       ├── user/                     # 用户 + 关注/粉丝（Redis ZSET 游标分页）
│       ├── event/                    # 活动 + 票档 + 最低票价
│       ├── order/                    # 订单：创建/支付/取消/退款/超时关单
│       ├── note/                     # 笔记 + 点赞 + 评论 + Redis ZSET 排行
│       ├── config/                   # Security / MyBatis-Plus / Redis / RabbitMQ
│       └── dto/                      # CursorPage / CommentVO 等
│
├── frontend/                         # Vue 3 前端
│   └── src/
│       ├── views/                    # 页面：EventList / Notes / NoteDetail / Mine / MyOrders ...
│       ├── components/               # BottomNav / EventCard
│       ├── api/                      # 接口：auth / event / order / user / note
│       ├── stores/                   # Pinia：user / order
│       └── utils/                    # request / format
│
└── 开发文档.md                        # 完整开发说明书
```

## 架构亮点

- **推荐流布隆消重**：基于 Redis BitMap 的分布式布隆过滤器，每用户独立 key（7天 TTL，~12KB），`pageSize × 3` 候选窗口 + 排名偏移无限滚动
- **关注流推模式收件箱**：发布时 fanout-on-write 写入所有粉丝收件箱 ZSET（最多 800 条），读取 O(log N)，新粉关注回填最近笔记，取关/删笔记实时清除
- **VO 缓存 + 读时修复**：`note:vo:{noteId}` Redis Hash 按需缓存（7天 TTL），列表批量读优先命中缓存，miss 回填 MySQL；`selectBatchIds` 发现已删除 ID 自动从全局 ZSET 清除
- **Redis 先行点赞 + 用户点赞 Set**：`note:like:count:{noteId}` String 计数器 INCR/DECR 先行（7天 TTL）+ `user:likes:{userId}` Set 记录谁点了赞（3天 TTL），O(1) SISMEMBER 判赞替代 MySQL 查询，MySQL 异步落库兜底
- **按用户懒加载 + TTL 过期**：冷启动仅同步 `note:latest` / `note:hottest` 全局池（1天 TTL），用户级数据（关注/粉丝 3天、收件箱 3天、点赞 3天、我的笔记 3天）在首次访问时从 MySQL 按需重建，不活跃用户不占内存
- **Pipeline 批量优化**：布隆检查/标记 + VO 缓存读取 + isLiked 判赞 + LPOP 全部 pipeline 化，推荐流从 350 次 Redis 往返降至 ~15 次，热路径延迟从 600ms 压至 **23-30ms**
- **双通道认证**：Cookie（SameSite=Lax）+ Authorization Bearer Header 兜底，解决浏览器 localhost Cookie 兼容性问题，Spring Security 返回 401 驱动前端跳转登录

## API 一览

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | /api/v1/auth/register | 注册 |
| POST | /api/v1/auth/login | 登录 |
| GET | /api/v1/event/list?page=&pageSize= | 活动列表（含最低票价） |
| GET | /api/v1/event/{id} | 活动详情+票档 |
| POST | /api/v1/order/create | 创建订单 |
| GET | /api/v1/order/list?status=&page= | 订单列表（分页） |
| GET | /api/v1/note/recommend-feed?cursor=&pageSize= | 推荐流（布隆消重 + 无限滚动） |
| GET | /api/v1/note/following-feed?cursor=&pageSize= | 关注流（推模式收件箱，需登录） |
| GET | /api/v1/note/my-notes?cursor=&pageSize= | 我的笔记（需登录） |
| POST | /api/v1/note/create | 发布笔记 |
| GET | /api/v1/note/{id} | 笔记详情 |
| POST | /api/v1/note/{id}/like | 点赞 |
| GET | /api/v1/note/{id}/comment | 评论列表（一级分页+children） |
| POST | /api/v1/note/{id}/comment | 发表评论 |
| POST | /api/v1/user/{id}/follow | 关注用户 |
| GET | /api/v1/user/follow/stats | 关注/粉丝统计（Redis ZCARD） |
| GET | /api/v1/user/follow/following?cursor=&pageSize= | 关注列表（游标分页） |
| GET | /api/v1/user/follow/fans?cursor=&pageSize= | 粉丝列表（游标分页） |

> 完整 API 文档及架构说明见 [开发文档.md](./开发文档.md)

## 数据库

8 张表：`user` → `event` → `ticket_category` → `order` → `user_follow` → `user_note` → `user_note_comment` → `note_like`

测试数据：100 用户 · 15 活动 · 44 票档 · 200 笔记 · 295 关注 · 496 点赞

## 许可证

MIT License
