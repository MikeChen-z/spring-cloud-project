package com.study.cloud.order.controller;

import com.study.cloud.common.result.Result;
import com.study.cloud.order.config.MqLagMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部接口：接收宿主机 lag-pusher.ps1 推送的积压数据。
 *
 * 为什么从外部推而不是 Java 侧自己拉？
 *   broker 4.9.7 自带的 mqadmin consumerProgress（PowerShell 脚本调用）与 broker 版本天然匹配；
 *   rocketmq-spring-boot-starter 2.3.0 传递依赖的 rocketmq-client 5.1.x 发的是 5.x 协议
 *   （request type 351），broker 4.9.7 不认识，会返回 "request type 351 not supported"。
 *   所以 Java 侧放弃主动拉取，改为宿主机脚本拉取后推送。
 *
 *   此接口仅监听宿主机 localhost（9000 → 8082），无需额外鉴权；
 *   学习环境无外网暴露，安全可控。
 */
@Slf4j
@RestController
@RequestMapping("/orders/internal")
@RequiredArgsConstructor
public class LagReportController {

    private final MqLagMonitor mqLagMonitor;

    /**
     * 宿主机 lag-pusher.ps1 每 15s 调用一次，推送所有消费组的 Diff Total。
     *
     * 请求体示例（mqadmin consumerProgress 输出解析后的 JSON）：
     *   {
     *     "order-backlog-consumer-group": 1540,
     *     "order-status-consumer-group": 0,
     *     "order-delay-consumer-group": 0,
     *     "order-dlq-consumer-group": 0
     *   }
     */
    @PostMapping("/lag-report")
    public Result<Map<String, Object>> lagReport(@RequestBody Map<String, Long> groupDiffs) {
        mqLagMonitor.report(groupDiffs);

        long total = groupDiffs.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("groups", groupDiffs.size());
        r.put("totalDiff", total);

        if (total > 0) {
            log.info("【lag-report】收到积压推送：{} 个消费组，合计 {} 条积压", groupDiffs.size(), total);
        }
        return Result.success(r);
    }
}
