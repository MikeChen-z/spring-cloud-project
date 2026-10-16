package com.study.cloud.order.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.study.cloud.order.entity.DeadLetter;
import com.study.cloud.order.mapper.DeadLetterMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocketMQ 积压 & 死信积压 Gauge 注册中心。
 *
 * 指标（Prometheus /actuator/prometheus 暴露，Prometheus 已抓取）：
 * rocketmq_consumer_lag Gauge {consumer_group} Diff Total（=BrokerOffset −
 * ConsumerOffset）
 * rocketmq_dead_letter_pending Gauge (无标签) t_dead_letter 里 status=0 的条数
 *
 * 更新机制：
 * lagCache（消费组 → Diff Total）由 **宿主机脚本 scripts/lag-pusher.ps1** 每 15s 从 broker
 * 自带的
 * mqadmin consumerProgress 拉取，通过 HTTP POST /internal/lag-report 推送到本类的 report
 * 方法。
 * 之所以不用 Java 侧的 rocketmq-tools：broker 是 4.9.7，但 rocketmq-spring-boot-starter
 * 2.3.0
 * 传递依赖的是 rocketmq-client 5.1.x，DefaultMQAdminExtImpl 发的 request type 351（5.x
 * 协议）
 * broker 4.9.7 不认识，直接返回 "request type 351 not supported"。绕过方法就是用 broker 容器
 * 自带的 mqadmin（版本天然匹配）+ 宿主机脚本调度。
 *
 * 死信积压 gauge 的闭包直接查 DB，Prometheus 每次 scrape 时自动取最新值。
 *
 * Gauge 闭包每次 scrape 时读 lagCache 当前值，注册后无需 update。
 * 已注册过 Gauge 的消费组不重复注册（Micrometer 不允许同名 Gauge）。
 */
@Slf4j
@Component
public class MqLagMonitor {

    /** 学习环境硬编码的已知消费组（启动时先注册一遍，避免第一次 scrape 前标签缺失） */
    private static final List<String> INITIAL_GROUPS = Arrays.asList(
            "order-backlog-consumer-group",
            "order-status-consumer-group",
            "order-delay-consumer-group",
            "order-dlq-consumer-group");

    /**
     * 消费组 → 最近一次 Diff Total。
     * 外部 lag-pusher.ps1 通过 report() 更新；Gauge 闭包每次 scrape 时读。
     */
    private final Map<String, Long> lagCache = new ConcurrentHashMap<>();

    /** 已注册过 Gauge 的消费组集合，避免重复注册 */
    private final Map<String, Boolean> registered = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;
    private final DeadLetterMapper deadLetterMapper;

    public MqLagMonitor(MeterRegistry meterRegistry, DeadLetterMapper deadLetterMapper) {
        this.meterRegistry = meterRegistry;
        this.deadLetterMapper = deadLetterMapper;
    }

    @PostConstruct
    public void start() {
        // 死信积压 Gauge：闭包直接查 DB（Prometheus 每次 scrape 时取最新值）
        Gauge.builder("rocketmq_dead_letter_pending", () -> getDeadLetterPending())
                .description("RocketMQ 死信待补偿条数（t_dead_letter WHERE status=0）")
                .register(meterRegistry);

        // 消费组 Gauge：启动时先给已知消费组注册一遍，避免第一次 scrape 前标签缺失
        for (String group : INITIAL_GROUPS) {
            registerLagGauge(group);
        }

        log.info("【MqLagMonitor】启动完成：4 个已知消费组 Gauge 已注册，lagCache 等待 lag-pusher.ps1 推送数据");
    }

    /**
     * 接收外部推送的积压数据。
     * 宿主机 scripts/lag-pusher.ps1 每 15s 拉一次 broker 数据后 POST 到这里。
     * 同时动态发现新消费组（自动补注册 Gauge）。
     *
     * @param groupDiffs 消费组名 → Diff Total
     */
    public void report(Map<String, Long> groupDiffs) {
        if (groupDiffs == null || groupDiffs.isEmpty())
            return;
        groupDiffs.forEach((group, diff) -> {
            lagCache.put(group, diff);
            registerLagGauge(group);
        });
    }

    // ---------- 内部辅助 ----------

    /**
     * 为某个消费组注册 lag Gauge（幂等：已注册过则跳过）。
     * Gauge 闭包每次 Prometheus scrape 时从 lagCache 读最新值。
     */
    private void registerLagGauge(String group) {
        registered.computeIfAbsent(group, g -> {
            Gauge.builder("rocketmq_consumer_lag", () -> lagCache.getOrDefault(g, 0L))
                    .description("RocketMQ 消费组积压条数（Diff Total = BrokerOffset − ConsumerOffset）")
                    .tag("consumer_group", g)
                    .register(meterRegistry);
            return Boolean.TRUE;
        });
    }

    private long getDeadLetterPending() {
        Long cnt = deadLetterMapper.selectCount(new LambdaQueryWrapper<DeadLetter>()
                .eq(DeadLetter::getStatus, 0));
        return cnt == null ? 0 : cnt;
    }
}
