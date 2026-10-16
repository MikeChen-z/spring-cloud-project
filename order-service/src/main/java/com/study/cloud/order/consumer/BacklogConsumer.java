package com.study.cloud.order.consumer;

import com.study.cloud.order.dto.BacklogMessage;
import com.study.cloud.order.entity.DeadLetter;
import com.study.cloud.order.mapper.DeadLetterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息积压演示消费者：人为放慢消费速度。
 *
 * 演示要点：
 * 1. 单实例消费能力 ≈ 消费线程数 ÷ (sleepMs/1000) 条每秒
 * —— 线程池大小 ÷ 单条耗时，就是消费上限
 * 2. 生产端用 sendOneWay 一次性灌入数千条，瞬时生产速率远超消费速率 → 积压
 * 3. mqadmin consumerProgress 看 Diff Total 上涨（积压）与回落（追平）
 *
 * 关键坑（本类实测踩到）：
 * 真正决定并发的是 consumeThreadNumber（rocketmq-spring 2.3.0 映射为
 * consumer.setConsumeThreadMin），不是 consumeThreadMax！
 * RocketMQ 消费线程池是 ThreadPoolExecutor + 【无界 LinkedBlockingQueue】，
 * 队列永远填不满 ⇒ maxPoolSize 永远不扩容，实际并发 = corePoolSize = consumeThreadMin。
 * 所以只调 consumeThreadMax 没有任何效果。
 *
 * 两种追平手段：
 * ① 调大消费线程数 consumeThreadNumber（=consumeThreadMin）：单实例并发提升（改注解 + 重建）
 * ② 多实例消费：--scale order-service=2，每实例独立线程池、各持 corePoolSize 线程
 *
 * ---------- 死信队列演示（同一消费者，一鱼两吃） ----------
 * 本类是 CONCURRENTLY（并发）消费：失败只影响该条消息，该条进入重试队列稍后重投，
 * 重试耗尽（maxReconsumeTimes=2，梯度退避约 10s + 30s）后由 Broker 自动投递到
 * 死信 Topic %DLQ%order-backlog-consumer-group。
 * 注意：若改成 ORDERLY（顺序），失败会无限挂起 queue、永不进死信——毒消息必须用并发消费。
 *
 * 毒消息制造：sleepMs < 0 ⇒ 直接抛异常。可用
 *   POST /api/orders/backlog-demo?count=3&sleepMs=-1
 * 灌入毒消息。
 *
 * 补偿回写：重投的消息带 dlqId，本条消费成功后把 t_dead_letter 置为"补偿成功"。
 *
 * 注意：不能每条都打日志——数千条日志会冲垮 Loki 与容器 stdout，
 * 这里每 200 条打一次进度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = "order-backlog-topic", selectorExpression = "BACKLOG", consumerGroup = "order-backlog-consumer-group",
        // 真正生效的并发上限：映射为 consumer.setConsumeThreadMin
        consumeThreadNumber = 20,
        // maxPoolSize 是摆设：无界队列下线程池不会扩容（见类注释）
        consumeThreadMax = 64,
        // 死信加速开关：默认 16 次梯度重试（最长 2h）对演示太长，压到 2 次（约 10s + 30s 后进死信）
        maxReconsumeTimes = 2)
public class BacklogConsumer implements RocketMQListener<BacklogMessage> {

    /** 累计消费条数（单实例内计数） */
    private static final AtomicLong CONSUMED = new AtomicLong();

    private final DeadLetterMapper deadLetterMapper;

    @Override
    public void onMessage(BacklogMessage message) {
        int sleepMs = message.getSleepMs() == null ? 0 : message.getSleepMs();

        // 毒消息：负的"处理耗时"语义上非法，直接抛异常触发重试→死信
        if (sleepMs < 0) {
            log.warn("【死信演示】毒消息处理失败：seq={} dlqId={}，抛异常触发 Broker 重试，耗尽后转入死信队列",
                    message.getSeq(), message.getDlqId());
            throw new RuntimeException("模拟消费失败（毒消息 sleepMs=" + sleepMs + "），重试耗尽后进入死信队列");
        }

        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // 补偿重投的消息带 dlqId：消费成功即回写死信状态，闭环"可补偿 + 不丢业务"
        if (message.getDlqId() != null) {
            DeadLetter update = new DeadLetter();
            update.setId(message.getDlqId());
            update.setStatus(1);
            deadLetterMapper.updateById(update);
            log.info("【死信补偿】重投消息消费成功，已回写：dlqId={} seq={}", message.getDlqId(), message.getSeq());
            return;
        }

        long n = CONSUMED.incrementAndGet();
        if (n % 200 == 0) {
            long latency = System.currentTimeMillis()
                    - (message.getSendTime() == null ? System.currentTimeMillis() : message.getSendTime());
            log.info("【积压演示】累计消费={} 本条端到端延迟={}ms 线程={}",
                    n, latency, Thread.currentThread().getName());
        }
    }
}
