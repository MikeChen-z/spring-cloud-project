package com.study.cloud.order.service;

import com.study.cloud.order.dto.OrderMessage;
import com.study.cloud.order.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 事务消息演示：下单 + 发消息的最终一致性。
 *
 * 与 {@link OrderService#createOrder}（Seata AT 强一致）的区别：
 * - 本地写订单和发消息通过 RocketMQ 事务消息绑定，保证最终一致
 * - 订单写入后立即发消息，若本地事务失败消息会被回滚丢弃
 *
 * 流程：
 * 1. 发半消息（消息暂存在 Broker，对消费者不可见）
 * 2. Broker 回调 {@link OrderTransactionListener#executeLocalTransaction} 执行本地事务
 * 3. 本地事务成功 → COMMIT → 消费者可见 → 加积分
 * 4. 本地事务失败 → ROLLBACK → 消息丢弃，消费者永远看不到
 * 5. 若步骤 2/3 间断网 → Broker 触发回查
 * {@link OrderTransactionListener#checkLocalTransaction}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTxService {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 事务消息下单：发送半消息，由监听器执行本地事务。
     *
     * @param userId       用户 ID
     * @param product      商品名称
     * @param amount       订单金额
     * @param forceUnknown true 时监听器返回 UNKNOWN，演示 Broker 回查
     */
    public void createOrderWithTxMessage(Long userId, String product, BigDecimal amount, boolean forceUnknown) {
        Order order = new Order();
        order.setUserId(userId);
        order.setProduct(product);
        order.setAmount(amount);
        order.setStatus(0);

        OrderMessage message = new OrderMessage(null, userId, product, amount);

        // sendMessageInTransaction：
        // 1. 先发半消息到 Broker（此时消费者不可见）
        // 2. Broker 回调监听器 executeLocalTransaction 执行本地事务
        // 3. 监听器返回 COMMIT/ROLLBACK/UNKNOWN 决定消息是否对消费者可见
        rocketMQTemplate.sendMessageInTransaction(
                "order-tx-topic:ORDER_TX",
                MessageBuilder.withPayload(message).build(),
                new Object[] { order, forceUnknown });

        log.info("【事务消息】半消息已发送，等待本地事务确认，userId={}, amount={}", userId, amount);
    }
}
