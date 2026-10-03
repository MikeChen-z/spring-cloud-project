package com.study.cloud.user.consumer;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.study.cloud.user.dto.OrderMessage;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务消息消费者：监听 order-tx-topic，给用户加积分。
 *
 * 与 {@link OrderPointsConsumer} 的区别：
 * - OrderPointsConsumer 消费 order-topic（Seata 下单后发的普通消息）
 * - 本消费者消费 order-tx-topic（RocketMQ 事务消息，只有本地事务 COMMIT 后才能收到）
 *
 * 关键点：
 * - 半消息阶段本消费者完全收不到消息（Broker 对消费者不可见）
 * - 只有 executeLocalTransaction 返回 COMMIT，或回查 checkLocalTransaction 返回 COMMIT 后才投递
 * - 返回 ROLLBACK 的消息会被 Broker 直接丢弃，永远不会进入本方法
 *
 * 幂等：与普通消息一样，消息可能重复投递，生产环境应用积分流水表去重。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-tx-topic",
        consumerGroup = "user-service-tx-points-consumer-group",
        selectorExpression = "ORDER_TX"
)
public class OrderTxPointsConsumer implements RocketMQListener<OrderMessage> {

    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(OrderMessage message) {
        Long userId = message.getUserId();
        int points = message.getAmount().intValue(); // 1元=1积分

        // 事务消息在发半消息时订单尚未插入，orderId 可能为 null（学习演示用 userId 加积分即可）
        log.info("【事务消息消费】收到已提交的事务消息，orderId={}, userId={}, 应加积分={}",
                message.getOrderId(), userId, points);

        User user = userMapper.selectById(userId);
        if (user == null) {
            log.error("用户不存在，userId={}", userId);
            return;
        }

        UpdateWrapper<User> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userId)
               .setSql("points = points + " + points);
        userMapper.update(null, wrapper);

        log.info("【事务消息消费】积分累加成功，userId={}, 当前积分={}", userId, user.getPoints() + points);
    }
}
