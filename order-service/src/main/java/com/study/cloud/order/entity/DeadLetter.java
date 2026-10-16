package com.study.cloud.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 死信记录实体，映射 order_db.t_dead_letter。
 *
 * 一条死信 = 一个消费组重试耗尽仍失败的消息。落库后：
 * - 可见：可查询、可在 DB 里直接查看原始报文
 * - 可补偿：body 里保存完整业务报文，人工修复后重投即可恢复业务
 * - 不丢业务：报文持久化，不依赖 Broker 侧的死信保留期
 */
@Data
@TableName("t_dead_letter")
public class DeadLetter {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** RocketMQ 消息 ID */
    private String msgId;

    /** 消息来源 Topic */
    private String topic;

    /** 消息来源消费组（死信 Topic 名 = %DLQ%该组名） */
    private String consumerGroup;

    /** 消息 Key */
    private String msgKey;

    /** 原始消息体（JSON），补偿重投的依据 */
    private String body;

    /** 进入死信的原因说明 */
    private String reason;

    /** 进入死信前的重试次数 */
    private Integer reconsumeTimes;

    /** 状态：0 待补偿 1 补偿成功 2 补偿失败 */
    private Integer status;

    /** 人工补偿重投次数 */
    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
