package com.study.cloud.order.service;

import com.study.cloud.order.client.UserClient;
import com.study.cloud.order.dto.OrderMessage;
import com.study.cloud.order.dto.UserDto;
import com.study.cloud.order.entity.Order;
import com.study.cloud.order.mapper.OrderMapper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 订单业务层。
 *
 * 把"查订单 + 调用户"的逻辑从 Controller 搬到 Service，
 * 并在"调用户"这一步加缓存，减少对 user-service 的远程调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final UserClient userClient;
    private final OrderMapper orderMapper;
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 远程查用户，带 Redis 缓存。
     *
     * @Cacheable：
     * - 缓存名 "users"，key 默认用方法参数（这里就是 id）
     * - 第一次调用：方法正常执行（发 Feign 请求），结果存入缓存
     * - 之后相同 id 的调用：直接从缓存返回，方法体根本不执行
     *
     * unless：SpEL 表达式，条件为 true 时结果不写入缓存。
     * 这里防止降级兜底数据（source 以"来自降级兜底"开头）被缓存，
     * 否则 user 恢复后 60 秒内请求会一直返回兜底数据。
     */
    @Cacheable(value = "users", unless = "#result != null and #result.source() != null and #result.source().startsWith('来自降级兜底')")
    public UserDto getUserWithCache(Long id) {
        log.info("缓存未命中，发起 Feign 调用查 user id={}", id);
        return userClient.getUser(id);
    }

    /**
     * 下单（Seata AT 全局事务入口 = TM）。
     *
     * 跨两个库、两个服务：
     * 分支1：order-service 往 order_db.t_order 插订单
     * 分支2：Feign 调 user-service 扣 study_cloud.t_user 余额（XID 自动透传）
     *
     * @GlobalTransactional：开启全局事务。
     * - 方法正常返回 → TC 通知两个分支一起提交（删 undo_log 快照）
     * - 方法抛出任何异常 → TC 通知两个分支按 undo_log 一起回滚
     * 即使订单 INSERT 已执行、余额已扣，物理数据也会恢复原状
     *
     * @param forceFail true 时在扣款成功后人为抛异常，用于观察两个库同时回滚
     */
    @GlobalTransactional(name = "create-order", rollbackFor = Exception.class)
    public Long createOrder(Long userId, String product, BigDecimal amount, boolean forceFail) {
        // 分支 1：本地写订单
        Order order = new Order();
        order.setUserId(userId);
        order.setProduct(product);
        order.setAmount(amount);
        order.setStatus(0);
        orderMapper.insert(order);
        log.info("【分支1】订单已写入 order_db，orderId={}", order.getId());

        // 分支 2：远程扣余额（失败会抛异常，含 fallback 中抛出的异常）
        userClient.deduct(userId, amount);
        log.info("【分支2】user-service 扣款成功，userId={}, amount={}", userId, amount);

        if (forceFail) {
            // 此时两分支都已执行完 SQL，抛异常后应看到：订单消失、余额恢复
            log.warn("模拟下单后异常，触发全局回滚");
            throw new RuntimeException("模拟异常：检验 Seata 全局回滚");
        }

        // ========== RocketMQ：下单成功后发送积分消息 ==========
        // 在全局事务提交后发送消息，确保消息不会在回滚时发出
        // syncSend：同步发送，等待 Broker 确认，可靠性最高
        OrderMessage msg = new OrderMessage(order.getId(), userId, product, amount);
        rocketMQTemplate.syncSend("order-topic:ORDER_CREATED", msg);
        log.info("【RocketMQ】积分消息已发送，orderId={}", order.getId());

        return order.getId();
    }
}
