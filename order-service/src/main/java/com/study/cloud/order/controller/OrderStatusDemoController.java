package com.study.cloud.order.controller;

import com.study.cloud.common.result.Result;
import com.study.cloud.order.dto.OrderStatusMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 顺序消息演示接口。
 *
 * 经网关：POST http://localhost:9000/api/orders/status-demo?orderId=301
 *         POST http://localhost:9000/api/orders/status-demo?orderId=301&failStep=3
 *
 * 一次请求连发 6 条状态消息（创建/已支付/打包中/已发货/配送中/已签收），
 * syncSendOrderly(hashKey=orderId) 内部用 MessageQueueSelector：
 *   queue = queues[hash(orderId) % queues.size()]
 * 保证同一 orderId 永远进同一 queue；不同 orderId 可能落不同 queue，可并行消费。
 */
@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderStatusDemoController {

    private final RocketMQTemplate rocketMQTemplate;

    private static final String[] STEP_NAMES = {"创建", "已支付", "打包中", "已发货", "配送中", "已签收"};

    /**
     * @param orderId  订单号（sharding key）
     * @param failStep 模拟消费失败的步骤（1~6），0 表示不模拟；
     *                 消费者在该步骤【首次】抛异常挂起 queue，重试后放行
     */
    @PostMapping("/status-demo")
    public Result<Map<String, Object>> statusDemo(
            @RequestParam Long orderId,
            @RequestParam(defaultValue = "0") int failStep) {

        for (int step = 1; step <= STEP_NAMES.length; step++) {
            OrderStatusMessage msg = new OrderStatusMessage(
                    orderId, step, STEP_NAMES[step - 1], System.currentTimeMillis(),
                    failStep == 0 ? null : failStep);
            // 顺序发送的关键：第三个参数 hashKey=orderId，同一 key 的消息进同一 queue
            rocketMQTemplate.syncSendOrderly(
                    "order-status-topic:STATUS_CHANGE",
                    MessageBuilder.withPayload(msg).build(),
                    String.valueOf(orderId));
        }
        log.info("【顺序消息】已按序发送 6 条状态消息，orderId={}, failStep={}", orderId, failStep);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("sent", STEP_NAMES.length);
        result.put("failStep", failStep);
        result.put("tip", "到 order-service 日志搜【顺序消息】，同一 orderId 的 step 必须严格 1->6 递增");
        return Result.success(result);
    }
}
