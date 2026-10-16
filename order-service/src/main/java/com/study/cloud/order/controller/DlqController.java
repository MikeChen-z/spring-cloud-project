package com.study.cloud.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.cloud.common.result.Result;
import com.study.cloud.order.dto.BacklogMessage;
import com.study.cloud.order.entity.DeadLetter;
import com.study.cloud.order.mapper.DeadLetterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信队列管理与补偿接口。
 *
 * 可见：GET  /api/orders/dlq/list?status=0        查询死信记录（含原始报文）
 * 可补偿：POST /api/orders/dlq/retry?limit=10      把"待补偿"的死信修复后重投原 Topic
 *
 * 补偿流程（闭环）：
 *   1. 读 t_dead_letter 中 status=0 的记录
 *   2. 反序列化 body 拿到原业务报文，人工修复（本演示用 sleepMs 参数覆盖导致失败的耗时）
 *   3. 带上 dlqId 重新投递到 order-backlog-topic
 *   4. 消费者消费成功后回写该记录 status=1（补偿成功）
 */
@Slf4j
@RestController
@RequestMapping("/orders/dlq")
@RequiredArgsConstructor
public class DlqController {

    /** 补偿重投的目标地址（与业务消费者订阅一致） */
    private static final String RESEND_DEST = "order-backlog-topic:BACKLOG";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeadLetterMapper deadLetterMapper;
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 死信列表：status 缺省查全部（0 待补偿 / 1 补偿成功 / 2 补偿失败）。
     */
    @GetMapping("/list")
    public Result<List<DeadLetter>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "50") int limit) {

        LambdaQueryWrapper<DeadLetter> wrapper = new LambdaQueryWrapper<DeadLetter>()
                .orderByDesc(DeadLetter::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        if (status != null) {
            wrapper.eq(DeadLetter::getStatus, status);
        }
        return Result.success(deadLetterMapper.selectList(wrapper));
    }

    /**
     * 补偿重投：把待补偿的死信逐条修复后重投原 Topic。
     *
     * @param limit   本次最多处理多少条待补偿记录
     * @param sleepMs 修复值——覆盖原报文中导致消费失败的 sleepMs（毒消息为 -1，
     *                补偿时给个非负值即"治好了"）。消费成功后由消费者回写 status=1。
     */
    @PostMapping("/retry")
    public Result<Map<String, Object>> retry(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int sleepMs) {

        List<DeadLetter> pending = deadLetterMapper.selectList(new LambdaQueryWrapper<DeadLetter>()
                .eq(DeadLetter::getStatus, 0)
                .orderByAsc(DeadLetter::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));

        int resent = 0;
        List<String> failures = new ArrayList<>();
        for (DeadLetter record : pending) {
            try {
                // 1. 反序列化原始报文
                BacklogMessage msg = MAPPER.readValue(record.getBody(), BacklogMessage.class);
                // 2. 人工修复 + 打上补偿标记
                msg.setSleepMs(sleepMs);
                msg.setDlqId(record.getId());
                // 3. 重投原 Topic
                rocketMQTemplate.syncSend(RESEND_DEST, msg);

                // 4. 累计重投次数（status 由消费者消费成功后回写为 1）
                DeadLetter update = new DeadLetter();
                update.setId(record.getId());
                update.setRetryCount(record.getRetryCount() == null ? 1 : record.getRetryCount() + 1);
                deadLetterMapper.updateById(update);
                resent++;
            } catch (Exception e) {
                log.error("【死信补偿】重投失败 dlqId={}", record.getId(), e);
                failures.add(record.getId() + ": " + e.getMessage());
            }
        }
        log.info("【死信补偿】待补偿={} 重投成功={} 失败={}", pending.size(), resent, failures.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingCount", pending.size());
        result.put("resent", resent);
        result.put("failures", failures);
        result.put("fix", "sleepMs 被覆盖为 " + sleepMs);
        result.put("tip", "重投后由 order-backlog-consumer-group 消费，成功后回写 t_dead_letter.status=1");
        return Result.success(result);
    }
}
