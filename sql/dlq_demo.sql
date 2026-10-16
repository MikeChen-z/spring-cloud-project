-- ============================================================
-- RocketMQ 死信队列演示：毒消息 → 重试耗尽 → 死信 → 落库可见 → 人工补偿
-- 前置：rocketmq_demo.sql / seata_demo.sql 已执行（order_db 已建）
-- ============================================================

USE order_db;

-- ---------- 死信记录表：让死信"可见"且"可补偿" ----------
-- 死信消息体（body）原样落库 —— 这是"不丢业务"的兜底：
-- 即便 Broker 侧死信被清理，DB 里仍留有完整原始报文，随时可人工重投。
-- 状态机：0 待补偿 → 1 补偿成功（消费者回写）；2 补偿失败（保留待人工介入）
CREATE TABLE IF NOT EXISTS t_dead_letter (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    msg_id          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'RocketMQ 消息 ID',
    topic           VARCHAR(128) NOT NULL DEFAULT '' COMMENT '消息来源 Topic',
    consumer_group  VARCHAR(128) NOT NULL DEFAULT '' COMMENT '消息来源消费组',
    msg_key         VARCHAR(128) NOT NULL DEFAULT '' COMMENT '消息 Key（本演示未设置，留空）',
    body            TEXT         NULL COMMENT '原始消息体（JSON，补偿重投的依据）',
    reason          VARCHAR(512) NOT NULL DEFAULT '' COMMENT '进入死信的原因说明',
    reconsume_times INT          NOT NULL DEFAULT 0 COMMENT '进入死信前的重试次数',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 待补偿 1 补偿成功 2 补偿失败',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '人工补偿重投次数',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '死信落库时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RocketMQ 死信记录（可见 + 可补偿）';

-- 确认
SELECT COUNT(*) AS dead_letter_rows FROM t_dead_letter;
