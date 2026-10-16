package com.study.cloud.order.controller;

import com.study.cloud.common.result.Result;
import com.study.cloud.order.dto.BacklogMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息积压演示接口。
 *
 * POST http://localhost:9000/api/orders/backlog-demo?count=6000&sleepMs=100
 *
 * 用 sendOneWay（单向发送，不等 Broker 响应，生产端吞吐最高）
 * 在循环里把 count 条消息一次性灌入 Broker，几乎瞬时完成；
 * 消费端每条 Sleep sleepMs 毫秒，形成"瞬时洪峰 + 慢消费"的积压。
 *
 * 观察积压：
 *   docker exec broker sh mqadmin consumerProgress -g order-backlog-consumer-group -n namesrv:9876
 *   → 看 Diff Total 从大数值回落
 */
@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class BacklogDemoController {

    private static final String DEST = "order-backlog-topic:BACKLOG";

    private final RocketMQTemplate rocketMQTemplate;

    @PostMapping("/backlog-demo")
    public Result<Map<String, Object>> backlogDemo(
            @RequestParam(defaultValue = "2000") int count,
            @RequestParam(defaultValue = "100") int sleepMs) {

        long start = System.currentTimeMillis();
        for (int i = 1; i <= count; i++) {
            // 单向发送：不等 Broker 响应，生产端吞吐最高，用来制造瞬时洪峰
            rocketMQTemplate.sendOneWay(DEST,
                    new BacklogMessage((long) i, System.currentTimeMillis(), sleepMs));
        }
        long cost = Math.max(System.currentTimeMillis() - start, 1);
        long rate = count == 0 ? 0 : count * 1000L / cost;
        log.info("【积压演示】已灌入 {} 条，耗时 {}ms，生产速率约 {} msg/s", count, cost, rate);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", count);
        r.put("sleepMs", sleepMs);
        r.put("sendCostMs", cost);
        r.put("sendRate", rate + " msg/s");
        r.put("singleThreadCapacity", (1000 / Math.max(sleepMs, 1)) + " msg/s per thread");
        r.put("tip", "立即执行 mqadmin consumerProgress -g order-backlog-consumer-group 观察 Diff Total");
        return Result.success(r);
    }
}
