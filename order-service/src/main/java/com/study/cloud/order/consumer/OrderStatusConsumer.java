package com.study.cloud.order.consumer;

import com.study.cloud.order.dto.OrderStatusMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 顺序消息消费者：订单状态流转。
 *
 * 与并发消费（ConsumeMode.CONCURRENTLY，本项目积分消费者用的就是它）的关键差异：
 *
 * 1. consumeMode = ORDERLY：
 *    - 消费端对每个 MessageQueue 加锁，同一 queue 同一时刻只有一个线程消费
 *    - 生产端用 syncSendOrderly(hashKey=orderId) 保证同一订单的消息进同一 queue
 *    - 两端配合 => 同一订单的状态消息严格按发送顺序被消费
 *
 * 2. 失败处理（顺序消费的核心代价）：
 *    - 并发消费失败：该条进入重试队列稍后重投，【不影响】其它消息继续消费
 *    - 顺序消费失败：返回 SUSPEND_CURRENT_QUEUE_A_MOMENT，当前 queue 被挂起，
 *      原地反复重试这条消息，直到成功——后续消息全部排队等待
 *      （本类用 failStep 参数模拟该行为，观察日志时间差）
 *
 * 3. 适用面：只用于"必须保序"的业务（订单状态机、账户流水）；
 *    普通通知类消息仍应用并发消费，吞吐高、失败不阻塞。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "order-status-topic",
        selectorExpression = "STATUS_CHANGE",
        consumerGroup = "order-status-consumer-group",
        consumeMode = ConsumeMode.ORDERLY
)
public class OrderStatusConsumer implements RocketMQListener<OrderStatusMessage> {

    /** 记录已模拟失败过的 orderId:step，首次失败抛异常，重试时放行 */
    private static final Set<String> FAILED_ONCE = ConcurrentHashMap.newKeySet();

    @Override
    public void onMessage(OrderStatusMessage message) {
        String failKey = message.getOrderId() + ":" + message.getStep();

        if (message.getFailStep() != null
                && message.getFailStep() > 0
                && message.getStep().equals(message.getFailStep())
                && FAILED_ONCE.add(failKey)) {
            log.warn("【顺序消息】模拟处理失败：orderId={} step={}，挂起当前 queue 原地重试，后续 step 排队等待",
                    message.getOrderId(), message.getStep());
            throw new RuntimeException("模拟顺序消费失败（SUSPEND_CURRENT_QUEUE_A_MOMENT）");
        }

        long costMs = System.currentTimeMillis() - message.getTimestamp();
        log.info("【顺序消息】消费成功：orderId={} step={}({}) 线程={} 生产至今{}ms",
                message.getOrderId(), message.getStep(), message.getStepName(),
                Thread.currentThread().getName(), costMs);
    }
}
