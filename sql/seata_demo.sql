-- ============================================================
-- Seata AT 演示：下单 = 写订单(order_db) + 扣余额(study_cloud)
-- 前置：两个库都已建好 undo_log（Seata AT 回滚快照表）
-- ============================================================

-- ---------- 1. order_db：订单表 ----------
CREATE DATABASE IF NOT EXISTS order_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE order_db;

CREATE TABLE IF NOT EXISTS t_order (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT        NOT NULL COMMENT '下单用户 id',
    product     VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '商品名称',
    amount      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '订单金额（同时从用户余额扣减）',
    status      TINYINT       NOT NULL DEFAULT 0 COMMENT '订单状态：0 已创建 1 已支付',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表（Seata AT 演示）';

-- ---------- 2. study_cloud：t_user 增加余额字段 ----------
USE study_cloud;

-- 已有列时重复执行会报错，学习环境忽略即可；新列默认给每个用户 1000 元
ALTER TABLE t_user
    ADD COLUMN balance DECIMAL(10,2) NOT NULL DEFAULT 1000.00 COMMENT '账户余额' AFTER password;

-- 确认数据
SELECT id, name, balance FROM t_user;
