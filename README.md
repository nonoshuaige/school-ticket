# 🎫 校园活动票务系统

> Vue 3 + Spring Boot + MySQL 全栈校园票务平台，支持活动售票、订单履约、笔记社区。

## 功能概览

| 模块 | 说明 |
|------|------|
| 🔐 用户 | 手机号注册/登录（JWT + Cookie）、关注/取关、粉丝统计 |
| 🎪 活动 | 热卖中/预热中分组、双列网格、真实最低票价、分页浏览 |
| 🎟️ 购票 | 选票档 → 确认下单（名额扣减）→ 模拟支付 → 倒计时关单 |
| 📦 订单 | 待支付→已支付→已核销 / 已取消 / 已退款，五态流转 |
| 📝 笔记 | 双列小红书风格卡片、点赞/取消、关注/取关、分页 |
| 📱 导航 | 底部三 Tab：购票、笔记、我的 |

## 技术栈

**后端**：Java 21 · Spring Boot 3.2.5 · Spring Security · MyBatis-Plus 3.5.7 · MySQL 8.0 · JWT

**前端**：Vue 3 (Composition API) · Vite 5 · Pinia · Axios · Vant 4

## 快速开始

### 环境要求

- JDK 21 · Maven 3.9+ · MySQL 8.0 · Node.js 20+

### 1. 启动 MySQL 并初始化

```bash
# 创建数据库并导入表结构和测试数据
mysql -u root -p --default-character-set=utf8mb4 -D school_ticket < backend/init.sql
```

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
# 运行在 http://localhost:8080，API 前缀 /api/v1
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
# 运行在 http://localhost:5173
```

### 4. 测试账号

| 手机号 | 密码 |
|--------|------|
| 13800138000 | 123456 |

## 项目结构

```
├── backend/                          # Spring Boot 后端
│   ├── init.sql                      # 建表 + 测试数据（7表/100用户/15活动/200笔记）
│   ├── gen_data.js                   # 测试数据生成脚本
│   └── src/main/java/com/schoolticket/
│       ├── auth/                     # 认证：登录/注册/JWT
│       ├── user/                     # 用户 + 关注/粉丝
│       ├── event/                    # 活动 + 票档 + 最低票价
│       ├── order/                    # 订单：创建/支付/取消/退款/超时关单
│       ├── note/                     # 笔记 + 点赞
│       └── config/                   # Security / MyBatis-Plus / WebMvc
│
├── frontend/                         # Vue 3 前端
│   └── src/
│       ├── views/                    # 页面：EventList/Notes/Mine/MyOrders/...
│       ├── components/               # BottomNav / EventCard
│       ├── api/                      # 接口：auth/event/order/user/note
│       ├── stores/                   # Pinia：user/order
│       └── utils/                    # request / format
│
└── 开发文档.md                        # 完整开发说明书
```

## API 一览

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | /api/v1/auth/register | 注册 |
| POST | /api/v1/auth/login | 登录 |
| GET | /api/v1/event/list?page=&pageSize= | 活动列表（含最低票价） |
| GET | /api/v1/event/{id} | 活动详情+票档 |
| POST | /api/v1/order/create | 创建订单 |
| GET | /api/v1/order/list?status=&page= | 订单列表（分页） |
| GET | /api/v1/note/list?page=&pageSize= | 笔记列表（含点赞/关注状态） |
| POST | /api/v1/note/{id}/like | 点赞 |
| POST | /api/v1/user/{id}/follow | 关注用户 |
| GET | /api/v1/user/follow/stats | 关注/粉丝统计 |

> 完整 API 文档见 [开发文档.md](./开发文档.md)

## 数据库

7 张表：`user` → `event` → `ticket_category` → `order` → `user_follow` → `user_note` → `note_like`

测试数据：100 用户 · 15 活动 · 44 票档 · 200 笔记 · 295 关注 · 496 点赞

## 许可证

MIT License
