package com.study.cloud.order.config;

import com.study.cloud.order.mapper.DeadLetterMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MqLagMonitor 的纯单元测试：不启 Spring 容器、不连 RocketMQ / MySQL。
 *
 * 为什么能这么测：
 * MqLagMonitor 的全部依赖就是构造注入的 MeterRegistry + DeadLetterMapper。
 * MeterRegistry 用 micrometer 自带的 SimpleMeterRegistry（内存实现，可读回 Gauge 当前值），
 * DeadLetterMapper 是 MyBatis-Plus 的接口，用 Mockito mock 掉。
 *
 * 为什么值得测（对应第二十一章刚做的功能）：
 * 1) report() 必须真的把值写进 lagCache，Gauge 闭包才读得到 —— 否则指标永远是空标签 0；
 * 2) Gauge 必须带正确的 consumer_group 标签 —— 否则 Prometheus 的告警规则匹配不到；
 * 3) 重复推送同一个消费组只能有一个 Gauge —— Micrometer 不允许同名同标签 Gauge 重复注册，
 *    这里依赖 registerLagGauge 的幂等（registered.computeIfAbsent），一旦回归就会抛异常。
 */
class MqLagMonitorTest {

    @Test
    void report_应写入缓存并带消费组标签且重复推送幂等() {
        DeadLetterMapper deadLetterMapper = mock(DeadLetterMapper.class);
        // selectCount 返回 Long（MyBatis-Plus 的 BaseMapper#selectCount 签名）
        when(deadLetterMapper.selectCount(any())).thenReturn(7L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqLagMonitor monitor = new MqLagMonitor(registry, deadLetterMapper);

        // 单测里没有 Spring 容器，@PostConstruct 不会自动执行，手动调一次注册 Gauge
        monitor.start();

        monitor.report(Map.of("order-dlq-consumer-group", 42L));
        // 第二次推送同一个消费组：Gauge 已注册，不应重复注册、也不应抛异常
        monitor.report(Map.of("order-dlq-consumer-group", 42L));

        // 断言 1：report() 写进 lagCache 的值能被带标签的 Gauge 读到
        assertThat(registry.get("rocketmq_consumer_lag")
                .tag("consumer_group", "order-dlq-consumer-group")
                .gauge().value()).isEqualTo(42d);

        // 断言 2：同一个消费组只有一个 Gauge（幂等）
        assertThat(registry.get("rocketmq_consumer_lag")
                .tag("consumer_group", "order-dlq-consumer-group")
                .gauges()).hasSize(1);

        // 断言 3：死信积压 Gauge 的闭包确实去查了 DB（读 DB 时执行 mapper）
        assertThat(registry.get("rocketmq_dead_letter_pending").gauge().value()).isEqualTo(7d);
    }
}
