package com.study.cloud.order.consumer;

import com.study.cloud.order.entity.DeadLetter;
import com.study.cloud.order.mapper.DeadLetterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 死信队列消费者：让死信"可见"。
 *
 * 死信机制（RocketMQ 4.x，零额外配置）：
 * - 消费组 order-backlog-consumer-group 的消费者把消息消费失败并重试耗尽后，
 *   Broker 自动把该消息投递到固定命名的死信 Topic：%DLQ%<consumerGroup>。
 * - Topic 由 Broker 自动创建，无需人工建立；消息体（body）保持原样（仍是 JSON 业务报文）。
 * - 死信 Topic 里的消息【不会自动重投】，只能靠人工/程序补偿——这正是它的价值：
 *   把"无法处理"的消息隔离出来，避免毒消息无限重试拖垮正常消费。
 *
 * 本消费者用【另一个消费组 order-dlq-consumer-group】订阅该死信 Topic，
 * 把每条死信落库到 t_dead_letter（body 原样保存），实现：
 *   ① 可见——可查询、可在 DB 直接查看原始报文
 *   ② 可补偿——保存完整报文，配合重投接口人工修复后重发
 *   ③ 不丢业务——持久化到 MySQL，不依赖 Broker 死信的保留期
 *
 * 注意泛型用 MessageExt 而非 BacklogMessage：DLQ 监听需要读取 msgId/topic/属性等
 * 消息元数据（RocketMQListener<MessageExt> 时 rocketmq-spring 不做反序列化，直接透传原始消息）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "%DLQ%order-backlog-consumer-group",
        selectorExpression = "*",
        consumerGroup = "order-dlq-consumer-group")
public class DlqConsumer implements RocketMQListener<MessageExt> {

    /** 死信来源消费组（死信 Topic 名 = %DLQ%该组名） */
    private static final String SOURCE_GROUP = "order-backlog-consumer-group";

    /** 死信来源 Topic：backlog 演示消息本就从 order-backlog-topic 发出 */
    private static final String SOURCE_TOPIC = "order-backlog-topic";

    private final DeadLetterMapper deadLetterMapper;

    @Override
    public void onMessage(MessageExt msg) {
        String body = msg.getBody() == null ? "" : new String(msg.getBody(), StandardCharsets.UTF_8);

        // RETRY_TOPIC 属性在重试链路里保留原始 topic；取不到则回退到已知的来源 topic
        String originTopic = msg.getProperty(MessageConst.PROPERTY_RETRY_TOPIC);
        if (originTopic == null || originTopic.isBlank()) {
            originTopic = SOURCE_TOPIC;
        }

        DeadLetter record = new DeadLetter();
        record.setMsgId(msg.getMsgId());
        record.setTopic(originTopic);
        record.setConsumerGroup(SOURCE_GROUP);
        record.setMsgKey(msg.getProperty(MessageConst.PROPERTY_KEYS) == null ? "" : msg.getProperty(MessageConst.PROPERTY_KEYS));
        record.setBody(body);
        record.setReason("消费失败重试耗尽，由 Broker 自动转入死信队列（%DLQ%" + SOURCE_GROUP + "）");
        record.setReconsumeTimes(msg.getReconsumeTimes());
        record.setStatus(0);
        record.setRetryCount(0);

        deadLetterMapper.insert(record);
        log.warn("【死信落库】msgId={} 原topic={} 已写入 t_dead_letter(id={})，等待人工补偿；body={}",
                msg.getMsgId(), originTopic, record.getId(), body);
    }
}
