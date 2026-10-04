package com.study.cloud.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单创建消息（与 order-service 中的定义保持一致，消费者用同一个类反序列化）。
 *
 * 关于序列化：
 * rocketmq-spring 的 RocketMQTemplate 默认用 Jackson JSON 传输消息体，
 * 跨服务按【字段名】匹配，两个 DTO 包名不同也能互通，不依赖 Serializable。
 * 这里 implements Serializable + serialVersionUID 仅为防御性保留
 * （若以后切换 JDK 序列化、或对象进 Redis JDK 序列化时可用）。
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
     * 消费者据此写积分流水，保证同一条消息重复投递时只加一次积分
     */
    private String bizNo;

    /** 下单用户 ID */
    private Long userId;

    /** 商品名称 */
    private String product;

    /** 订单金额 */
    private BigDecimal amount;
}
