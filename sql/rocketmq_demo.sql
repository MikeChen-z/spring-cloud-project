-- ============================================================
-- RocketMQ 演示：下单后发消息，user-service 消费后加积分
-- 前置：Seata AT 演示已完成（t_user 已有 balance 字段）
-- ============================================================

-- ---------- 1. study_cloud：t_user 增加积分字段 ----------
USE study_cloud;

-- 积分：下单金额 1 元 = 1 积分（演示用简化规则）
ALTER TABLE t_user
    ADD COLUMN points INT NOT NULL DEFAULT 0 COMMENT '用户积分' AFTER balance;

-- 确认数据
SELECT id, name, balance, points FROM t_user;
