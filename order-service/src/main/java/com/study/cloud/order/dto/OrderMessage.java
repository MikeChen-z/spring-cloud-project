package com.study.cloud.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单创建消息（生产者 → Broker → 消费者 的载体）。
 *
 * 关于序列化：
 * rocketmq-spring 的 RocketMQTemplate 默认用 Jackson JSON 传输消息体，
 * 跨服务按【字段名】匹配，与消费端的同名 DTO 包名不同也能互通。
 * implements Serializable + serialVersionUID 仅为防御性保留。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单 ID */
    private Long orderId;

    /**
     * 幂等键（业务唯一编号）。
     * 普通消息：ORDER-{orderId}；事务消息：发半消息前预生成的 UUID
     * （事务消息发半消息时订单还没插入，orderId 为 null，无法用 orderId 去重）
     */
    private String bizNo;

    /** 下单用户 ID */
    private Long userId;

    /** 商品名称 */
    private String product;

    /** 订单金额 */
    private BigDecimal amount;
}
