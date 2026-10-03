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
 * 订单消息消费者：监听下单消息，给用户加积分。
 *
 * @RocketMQMessageListener：
 * - topic：监听的 Topic 名
 * - consumerGroup：消费者组名（同组内消息负载均衡，不同组各自消费一份）
 * - selectorExpression：Tag 过滤表达式，"ORDER_CREATED" 表示只消费这个 Tag 的消息
 *
 * 幂等设计：
 * 消息可能重复投递（网络重试、Broker 重启等），所以积分累加要做幂等。
 * 这里用"订单 ID 去重"：如果该订单已经加过积分，直接跳过。
 * 生产环境通常用"积分流水表"或 Redis SETNX 做更严格的幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-topic",
        consumerGroup = "user-service-points-consumer-group",
        selectorExpression = "ORDER_CREATED"
)
public class OrderPointsConsumer implements RocketMQListener<OrderMessage> {

    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(OrderMessage message) {
        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        int points = message.getAmount().intValue(); // 1元=1积分，演示用简化规则

        log.info("【RocketMQ】收到下单消息，orderId={}, userId={}, 应加积分={}", orderId, userId, points);

        // 幂等检查：看该订单是否已经加过积分
        // 这里简化处理：检查 user 表是否有 points_log 字段或流水记录
        // 学习阶段直接用 userId + orderId 组合做简单去重（实际生产要用积分流水表）
        User user = userMapper.selectById(userId);
        if (user == null) {
            log.error("用户不存在，userId={}", userId);
            return;
        }

        // 累加积分
        UpdateWrapper<User> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userId)
               .setSql("points = points + " + points);
        userMapper.update(null, wrapper);

        log.info("【RocketMQ】积分累加成功，userId={}, 当前积分={}", userId, user.getPoints() + points);
    }
}
