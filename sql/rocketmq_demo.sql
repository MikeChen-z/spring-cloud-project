-- ============================================================
-- RocketMQ 演示：下单后发消息，user-service 消费后加积分
-- 前置：Seata AT 演示已完成（t_user 已有 balance 字段）
-- ============================================================

-- ---------- 1. study_cloud：t_user 增加积分字段 ----------
USE study_cloud;

-- 积分：下单金额 1 元 = 1 积分（演示用简化规则）
ALTER TABLE t_user
    ADD COLUMN points INT NOT NULL DEFAULT 0 COMMENT '用户积分' AFTER balance;

-- ---------- 2. 积分流水表：消息消费幂等的依据 ----------
-- 每条加积分消息对应一条流水，biz_no 唯一键保证同一条消息无论投递多少次只加一次积分
-- biz_no 规则：普通消息 ORDER-{orderId}；事务消息为生产者预生成的 UUID（半消息阶段 orderId 尚不存在）
CREATE TABLE IF NOT EXISTS t_points_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_no       VARCHAR(64)  NOT NULL COMMENT '幂等键：ORDER-{orderId} 或事务消息 UUID',
    order_id     BIGINT       NULL     COMMENT '订单 ID（事务消息半消息阶段可能为空）',
    user_id      BIGINT       NOT NULL COMMENT '用户 ID',
    points       INT          NOT NULL COMMENT '本次增加的积分',
    source_topic VARCHAR(32)  NOT NULL COMMENT '来源 Topic：order-topic / order-tx-topic',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_no (biz_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分变动流水（消息消费幂等表）';

-- 确认数据
SELECT id, name, balance, points FROM t_user;
