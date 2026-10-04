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
 * 订单消息消费者：监听下单消息，给用户加积分。
 *
 * @RocketMQMessageListener：
 * - topic：监听的 Topic 名
 * - consumerGroup：消费者组名（同组内消息负载均衡，不同组各自消费一份）
 * - selectorExpression：Tag 过滤表达式，"ORDER_CREATED" 表示只消费这个 Tag 的消息
 *
 * 幂等设计（消费端幂等 = MQ 可靠性的最后一公里）：
 * RocketMQ 的投递语义是"至少一次"，网络重试、Consumer 重启、rebalance 都可能
 * 让同一条消息被投递多次，所以"加积分"绝不能裸执行 points = points + N。
 *
 * 做法：t_points_log.biz_no 唯一索引 + 先插流水再加积分：
 * 1. 插入流水成功 → 首次消息，继续加积分（两步同一本地事务，原子提交）
 * 2. 抛出 DuplicateKeyException → 重复消息，直接 return（正常返回即 ACK，Broker 不再重投）
 *
 * 为什么不用 select 查一下再 insert？
 * "先查后插"在并发下有竞态（两个线程同时查到不存在），唯一索引才是最终兜底。
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
    private final PointsLogMapper pointsLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(OrderMessage message) {
        String bizNo = message.getBizNo();
        Long userId = message.getUserId();
        int points = message.getAmount().intValue(); // 1元=1积分，演示用简化规则

        log.info("【RocketMQ】收到下单消息，bizNo={}, orderId={}, userId={}, 应加积分={}",
                bizNo, message.getOrderId(), userId, points);

        // 第 1 步：插入流水"占坑"。biz_no 唯一索引保证并发/重试下只有一次插入成功
        PointsLog pointsLog = new PointsLog();
        pointsLog.setBizNo(bizNo);
        pointsLog.setOrderId(message.getOrderId());
        pointsLog.setUserId(userId);
        pointsLog.setPoints(points);
        pointsLog.setSourceTopic("order-topic");
        try {
            pointsLogMapper.insert(pointsLog);
        } catch (DuplicateKeyException e) {
            // 唯一键冲突 = 这条消息以前已经处理过 → 幂等跳过
            // 正常 return 向 Broker 确认消费（ACK），消息不会再投递
            log.warn("【幂等拦截】bizNo={} 已存在积分流水，重复消息直接跳过，不再加积分", bizNo);
            return;
        }

        // 第 2 步：首次消息，累加积分。与流水插入在同一事务，流水成功但加分失败会一起回滚，
        // 消息会重新投递（此时流水也回滚了，重投能再次插入成功，不会误判为重复）
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalStateException("用户不存在，userId=" + userId + "，抛异常让消息重试");
        }
        UpdateWrapper<User> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userId)
               .setSql("points = points + " + points);
        userMapper.update(null, wrapper);

        log.info("【RocketMQ】积分累加成功，bizNo={}, userId={}, 新增积分={}, 当前积分={}",
                bizNo, userId, points, user.getPoints() + points);
    }
}
