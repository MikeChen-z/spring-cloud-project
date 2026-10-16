package com.study.cloud.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 顺序消息 DTO：订单状态流转事件。
 * 同一 orderId 的多条消息按 step 升序发送，消费者必须按相同顺序收到。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusMessage {

    /** 订单号（sharding key：同一 orderId 永远进入同一 queue） */
    private Long orderId;

    /** 状态步骤序号：1=创建 2=已支付 3=打包中 4=已发货 5=配送中 6=已签收 */
    private Integer step;

    /** 步骤名称（仅用于日志展示） */
    private String stepName;

    /** 发送时间戳（毫秒），用于观察生产/消费间隔 */
    private Long timestamp;

    /**
     * 模拟失败步骤：消费者收到 step==failStep 的消息时【首次】抛异常，
     * 触发顺序消费的队列挂起重试；重试时放行。为 0/null 表示不模拟。
     * 用于演示顺序消费的核心代价：一条失败，整个 queue 后续消息排队等待。
     */
    private Integer failStep;
}
