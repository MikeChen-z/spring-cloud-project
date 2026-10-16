package com.study.cloud.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息积压演示 DTO。
 *
 * sleepMs 由生产端指定，消费端据此模拟"慢业务处理"，
 * 用来人为制造 生产速率 >> 消费速率 的持续积压场景。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BacklogMessage {

    /** 消息序号（1..count），用于确认无丢失 */
    private Long seq;

    /** 发送时间戳（毫秒），用于计算端到端延迟 */
    private Long sendTime;

    /** 消费时模拟的业务耗时（毫秒）；负数表示"毒消息"，消费者据此抛异常触发重试→死信 */
    private Integer sleepMs;

    /**
     * 死信补偿标记：补偿重投时带回原死信记录 id。
     * 消费者成功处理后据此回写 t_dead_letter.status=1，形成"补偿闭环"；
     * 正常业务消息该字段为 null。
     */
    private Long dlqId;

    /** 便捷构造：普通业务消息（无死信补偿标记） */
    public BacklogMessage(Long seq, Long sendTime, Integer sleepMs) {
        this(seq, sendTime, sleepMs, null);
    }
}
