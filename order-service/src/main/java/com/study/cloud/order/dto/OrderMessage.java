package com.study.cloud.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单创建消息（生产者 → Broker → 消费者 的载体）。
 *
 * 为什么要实现 Serializable：
 * RocketMQ 传输的是字节数组，默认用 JDK 序列化。
 * 不实现 Serializable 会抛 NotSerializableException。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单 ID */
    private Long orderId;

    /** 下单用户 ID */
    private Long userId;

    /** 商品名称 */
    private String product;

    /** 订单金额 */
    private BigDecimal amount;
}
