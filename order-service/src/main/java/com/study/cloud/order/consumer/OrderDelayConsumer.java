package com.study.cloud.order.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.study.cloud.order.dto.OrderMessage;
import com.study.cloud.order.entity.Order;
import com.study.cloud.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 延迟消息消费者：订单超时自动取消。
 *
 * 工作流程：
 * 1. 下单成功后，OrderService 发一条延迟消息（delayLevel=3, 10s 后投递）
 * 2. 10s 后 Broker 把消息从延迟队列改写到真实 queue，本消费者收到
 * 3. CAS 条件更新取消订单：
 *    UPDATE t_order SET status=2 WHERE id=? AND status=0
 *    - 影响 1 行：说明 10s 内未支付，已取消
 *    - 影响 0 行：说明已支付（status=1）或已取消（status=2，重复投递幂等），跳过
 *
 * 为什么不用"先 selectById 判断再 updateById"：
 *   用户可能在 SELECT 与 UPDATE 之间完成支付，无条件 UPDATE 会把已支付订单覆盖成已取消
 *   （丢失更新）。条件 UPDATE 把判断与写入合并为一条原子语句，由 MySQL 行锁与 payOrder
 *   的条件更新互斥，两者只有一个能成功。
 *
 * 延迟级别对照（RocketMQ 4.x 固定级别）：
 *   level 1=1s  2=5s  3=10s  4=30s  5=1m  6=2m  ...  14=10m
 * 学习环境用 level=3（10s）方便观察；生产环境用 level=14（10分钟）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-delay-topic",
        selectorExpression = "ORDER_DELAY_CHECK",
        consumerGroup = "order-delay-consumer-group"
)
public class OrderDelayConsumer implements RocketMQListener<OrderMessage> {

    private final OrderMapper orderMapper;

    @Override
    public void onMessage(OrderMessage message) {
        Long orderId = message.getOrderId();
        log.info("【延迟消息】收到订单超时检查消息，orderId={}", orderId);

        // CAS：仅当订单仍是未支付（status=0）时才取消
        int rows = orderMapper.update(null,
                new LambdaUpdateWrapper<Order>()
                        .eq(Order::getId, orderId)
                        .eq(Order::getStatus, 0)
                        .set(Order::getStatus, 2));

        if (rows == 1) {
            log.warn("【延迟消息】订单超时未支付，已自动取消（CAS 命中），orderId={}", orderId);
            return;
        }

        // rows=0：订单不存在（事务回滚）或状态已变（已支付/已取消），查一次仅用于打日志
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            log.warn("【延迟消息】订单不存在，可能已被回滚，orderId={}", orderId);
        } else {
            log.info("【延迟消息】订单状态={}，无需取消（1=已支付 2=已取消），orderId={}",
                    order.getStatus(), orderId);
        }
    }
}