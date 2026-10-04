package com.study.cloud.user.consumer;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.study.cloud.user.dto.OrderMessage;
import com.study.cloud.user.entity.PointsLog;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.mapper.PointsLogMapper;
import com.study.cloud.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
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
 * 幂等：事务消息的 bizNo 是生产者发半消息前预生成的 UUID（此时 orderId 尚不存在），
 * Broker 重投/回查后投递的都是同一条消息、同一个 UUID，流水唯一索引同样能去重。
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
    private final PointsLogMapper pointsLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(OrderMessage message) {
        String bizNo = message.getBizNo();
        Long userId = message.getUserId();
        int points = message.getAmount().intValue(); // 1元=1积分

        log.info("【事务消息消费】收到已提交的事务消息，bizNo={}, orderId={}, userId={}, 应加积分={}",
                bizNo, message.getOrderId(), userId, points);

        // 第 1 步：流水占坑，UUID 幂等键冲突说明同一事务消息被重复投递
        PointsLog pointsLog = new PointsLog();
        pointsLog.setBizNo(bizNo);
        pointsLog.setOrderId(message.getOrderId());
        pointsLog.setUserId(userId);
        pointsLog.setPoints(points);
        pointsLog.setSourceTopic("order-tx-topic");
        try {
            pointsLogMapper.insert(pointsLog);
        } catch (DuplicateKeyException e) {
            log.warn("【幂等拦截】bizNo={} 已存在积分流水，事务消息重复投递，直接跳过", bizNo);
            return;
        }

        // 第 2 步：首次消息，累加积分
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalStateException("用户不存在，userId=" + userId + "，抛异常让消息重试");
        }
        UpdateWrapper<User> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userId)
               .setSql("points = points + " + points);
        userMapper.update(null, wrapper);

        log.info("【事务消息消费】积分累加成功，bizNo={}, userId={}, 新增积分={}, 当前积分={}",
                bizNo, userId, points, user.getPoints() + points);
    }
}
