package com.study.cloud.order.service;

import com.study.cloud.order.entity.Order;
import com.study.cloud.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

/**
 * RocketMQ 事务消息监听器：处理半消息的 commit/rollback/回查。
 *
 * @RocketMQTransactionListener：标记这个类为事务消息监听器，
 *                                             自动监听 sendMessageInTransaction
 *                                             发送的半消息。
 *
 *                                             核心机制：
 *                                             1.
 *                                             executeLocalTransaction：收到半消息后，执行业务本地事务
 *                                             - COMMIT → 消息对消费者可见
 *                                             - ROLLBACK → 消息丢弃
 *                                             - UNKNOWN → Broker 在稍后触发
 *                                             checkLocalTransaction 回查
 *
 *                                             2. checkLocalTransaction：Broker
 *                                             回查时调用，检查本地事务状态
 *                                             - 查到记录 → COMMIT（本地事务确实成功了）
 *                                             - 查不到 → ROLLBACK（本地事务确实失败了/没执行）
 *                                             - 实在不确定 → 继续 UNKNOWN，Broker 会再查
 */
@Slf4j
@RequiredArgsConstructor
@RocketMQTransactionListener
public class OrderTransactionListener implements RocketMQLocalTransactionListener {

    private final OrderMapper orderMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            Object[] args = (Object[]) arg;
            Order order = (Order) args[0];
            boolean forceUnknown = (boolean) args[1];

            // 本地事务：写订单到 order_db
            orderMapper.insert(order);
            log.info("【本地事务】订单已写入 order_db，orderId={}", order.getId());

            if (forceUnknown) {
                // 故意返回 UNKNOWN，演示 Broker 回查机制
                log.warn("【事务监听】executeLocalTransaction 返回 UNKNOWN，等待 Broker 回查");
                return RocketMQLocalTransactionState.UNKNOWN;
            }

            log.info("【事务监听】本地事务成功，返回 COMMIT，orderId={}", order.getId());
            return RocketMQLocalTransactionState.COMMIT;

        } catch (Exception e) {
            log.error("【事务监听】本地事务异常，返回 ROLLBACK", e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        log.info("【事务监听】Broker 触发回查，msg={}", msg);

        // 回查逻辑：检查订单是否已写入数据库
        // 学习阶段简化：假设 UNKNOWN 场景下数据已写入（executeLocalTransaction 中先插入再返回 UNKNOWN）
        // 实际生产环境：用业务流水号从数据库查记录，查到 → COMMIT，查不到 → ROLLBACK
        log.info("【事务监听】回查：查到订单记录，返回 COMMIT");
        return RocketMQLocalTransactionState.COMMIT;
    }
}
