-- ============================================================
-- 校园活动票务系统 - 数据库初始化脚本
-- 数据库：school_ticket (utf8mb4)
-- ============================================================

-- CREATE DATABASE IF NOT EXISTS school_ticket
--   DEFAULT CHARACTER SET utf8mb4
--   DEFAULT COLLATE utf8mb4_general_ci;
-- USE school_ticket;

-- ============================================================
-- 用户表
-- ============================================================
DROP TABLE IF EXISTS `order`;
DROP TABLE IF EXISTS `ticket_category`;
DROP TABLE IF EXISTS `event`;
DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
  `user_id`      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `phone`        VARCHAR(20)  NOT NULL                COMMENT '手机号（登录账号）',
  `password`     VARCHAR(255) NOT NULL                COMMENT '密码（BCrypt加密）',
  `nickname`     VARCHAR(50)  DEFAULT ''              COMMENT '昵称',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================================
-- 活动表
-- ============================================================
CREATE TABLE `event` (
  `event_id`        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '活动ID',
  `title`           VARCHAR(100) NOT NULL                COMMENT '活动标题',
  `description`     TEXT                                 COMMENT '活动描述',
  `organizer`       VARCHAR(100) NOT NULL                COMMENT '主办方',
  `venue`           VARCHAR(100) NOT NULL                COMMENT '场地',
  `poster_url`      VARCHAR(255)                         COMMENT '海报URL',
  `sale_start_time` DATETIME     NOT NULL                COMMENT '开售时间',
  `sale_end_time`   DATETIME     NOT NULL                COMMENT '停售时间',
  `event_start_time` DATETIME    NOT NULL                COMMENT '活动开始时间',
  `event_end_time`  DATETIME     NOT NULL                COMMENT '活动结束时间',
  `status`          TINYINT      NOT NULL DEFAULT 0      COMMENT '0=未发布 1=售票中 2=已结束 3=已下架',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_status` (`status`),
  KEY `idx_sale_time` (`sale_start_time`, `sale_end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动表';

-- ============================================================
-- 票档表
-- ============================================================
CREATE TABLE `ticket_category` (
  `ticket_id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '票类ID',
  `event_id`           BIGINT        NOT NULL                COMMENT '所属活动',
  `name`               VARCHAR(50)   NOT NULL                COMMENT '票档名',
  `price`              DECIMAL(10,2) NOT NULL                COMMENT '单价',
  `total_quantity`     INT           NOT NULL                COMMENT '总库存',
  `remaining_quantity` INT           NOT NULL                COMMENT '剩余可售数',
  `description`        VARCHAR(255)                          COMMENT '票档说明',
  `create_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ticket_id`),
  KEY `idx_event_id` (`event_id`),
  CONSTRAINT `fk_ticket_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票档表';

-- ============================================================
-- 订单表
-- ============================================================
CREATE TABLE `order` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `order_no`      VARCHAR(64)   NOT NULL                COMMENT '订单号（业务唯一标识）',
  `user_id`       BIGINT        NOT NULL                COMMENT '用户ID',
  `ticket_id`     BIGINT        NOT NULL                COMMENT '票档ID',
  `quantity`      INT           NOT NULL DEFAULT 1      COMMENT '购买张数',
  `total_price`   DECIMAL(10,2) NOT NULL                COMMENT '总价',
  `status`        TINYINT       NOT NULL DEFAULT 0      COMMENT '0=待支付 1=已支付 2=已取消 3=已退款 4=已核销',
  `expire_time`   DATETIME      NOT NULL                COMMENT '支付截止时间',
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `paid_time`     DATETIME                               COMMENT '支付时间',
  `cancel_time`   DATETIME                               COMMENT '取消时间',
  `refund_time`   DATETIME                               COMMENT '退款时间',
  `use_time`      DATETIME                               COMMENT '核销时间',
  `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  CONSTRAINT `fk_order_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`user_id`),
  CONSTRAINT `fk_order_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `ticket_category` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- ============================================================
-- 初始化数据：测试用户
-- 手机号 13800138000 / 密码 123456
-- ============================================================
INSERT INTO `user` (`phone`, `password`, `nickname`) VALUES
('13800138000', '$2b$10$FFazAHt3BPFM90IXFHjuM.1858i7omXn9IDf0aDxoxYAbDcr3dlOS', '测试用户');

-- ============================================================
-- 初始化数据：4 场活动
-- ============================================================
INSERT INTO `event` (`event_id`, `title`, `description`, `organizer`, `venue`, `sale_start_time`, `sale_end_time`, `event_start_time`, `event_end_time`, `status`) VALUES
(1,
 '毕业晚会「青春不散场」',
 '又是一年毕业季，让我们用一场盛大的晚会告别青春。校学生会倾力打造，节目涵盖歌舞、小品、乐队演出，还有神秘嘉宾登场。',
 '校学生会',
 '学校大礼堂',
 '2026-06-25 10:00:00',
 '2026-07-09 20:00:00',
 '2026-07-10 19:30:00',
 '2026-07-10 22:00:00',
 1),

(2,
 '校园歌手大赛总决赛',
 '经过层层选拔，12位校园歌手将站上总决赛舞台。专业评委团现场打分，大众评审团参与投票，谁是今年的校园歌王？',
 '校团委 × 音乐协会',
 '体育馆',
 '2026-07-01 10:00:00',
 '2026-07-19 18:00:00',
 '2026-07-20 18:00:00',
 '2026-07-20 21:30:00',
 0),

(3,
 '新年联欢晚会',
 '辞旧迎新，校艺术团精心筹备了一场高水准的文艺汇演，交响乐、合唱、民族舞、魔术等节目精彩纷呈。',
 '校艺术团',
 '音乐厅',
 '2026-07-15 10:00:00',
 '2026-07-31 18:00:00',
 '2026-08-01 19:00:00',
 '2026-08-01 22:00:00',
 0),

(4,
 '校园戏剧节「仲夏夜之梦」',
 '戏剧社年度大戏——莎士比亚经典喜剧《仲夏夜之梦》改编版。古典与现代的碰撞，带你进入一个奇幻的夏夜梦境。',
 '戏剧社',
 '黑匣子剧场',
 '2026-07-10 10:00:00',
 '2026-07-24 20:00:00',
 '2026-07-25 19:30:00',
 '2026-07-25 21:00:00',
 0);

-- ============================================================
-- 初始化数据：11 个票档
-- ============================================================
INSERT INTO `ticket_category` (`ticket_id`, `event_id`, `name`, `price`, `total_quantity`, `remaining_quantity`, `description`) VALUES
(1, 1, 'VIP区',  288.00, 200, 200, '前排座椅 + 纪念品'),
(2, 1, '标准区', 188.00, 600, 600, '中部区域'),
(3, 1, '看台区',  88.00, 400, 400, '后方看台'),

(4, 2, '前排区', 128.00, 300, 300, '近距离观赛'),
(5, 2, '标准区',  68.00, 1000, 1000, '内场座椅'),
(6, 2, '站票区',  38.00, 700, 700, '后方站立区'),

(7, 3, 'VIP区',  388.00, 100, 100, '前 5 排 + 茶歇'),
(8, 3, '优选区', 238.00, 300, 300, '中区'),
(9, 3, '普通区', 128.00, 400, 400, '后区'),

(10, 4, '前排区', 88.00, 100, 100, '近距离观演'),
(11, 4, '标准区', 48.00, 200, 200, '阶梯式座位');
