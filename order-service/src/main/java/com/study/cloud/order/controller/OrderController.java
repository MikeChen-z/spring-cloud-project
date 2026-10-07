package com.study.cloud.order.controller;

import com.study.cloud.common.result.Result;
import com.study.cloud.order.dto.UserDto;
import com.study.cloud.order.service.OrderService;
import com.study.cloud.order.service.OrderTxService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单接口：演示一次请求中跨服务调用。
 * 调用链：浏览器 -> Gateway(验签+注入用户头) -> order-service -> (缓存/Feign) -> user-service
 *
 * 所有接口统一返回 {@link Result}。
 * 类上的 @Validated 用于开启【方法参数】约束校验，
 * 配合 @RequestParam 上的 @NotNull / @DecimalMin，非法参数在进方法前就被拦截返回 400。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderTxService orderTxService;

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(
            @PathVariable Long id,
            // 网关注入的登录用户信息；required=false 是为了兼容直连 8082 调试
            @RequestHeader(value = "X-User-Id", required = false) String currentUserId,
            @RequestHeader(value = "X-User-Name", required = false) String encodedUserName) {

        // 网关对中文用户名做了 URL 编码，这里解码还原
        String currentUserName = encodedUserName == null
                ? null
                : URLDecoder.decode(encodedUserName, StandardCharsets.UTF_8);
        log.info("当前登录用户：id={}, name={}", currentUserId, currentUserName);

        // 模拟查本地订单
        Map<String, Object> order = Map.of(
                "orderId", id,
                "product", "Spring Cloud 学习手册",
                "amount", 99.0,
                "source", "order-service:8082");

        // 查用户（带缓存）：命中时此方法不执行 Feign 调用
        UserDto user = orderService.getUserWithCache(id);

        // 用 LinkedHashMap 以便容纳可能为 null 的 currentUser（Map.of 不允许 null 值）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("user", user);
        result.put("currentUser", currentUserName == null ? "匿名（未经过网关/未登录）" : currentUserName);
        return Result.success(result);
    }

    /**
     * 写接口（创建订单）：仅用于演示 Sentinel「关联模式」。
     * 当此写接口 QPS 达到阈值时，关联规则会让读接口 GET:/orders/{id} 被限流。
     */
    @PostMapping
    public Result<Map<String, Object>> create() {
        log.info("创建订单（写接口）被调用");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("source", "order-service:8082");
        return Result.success(result);
    }

    /**
     * Seata 分布式事务演示接口：下单（写订单库 + 远程扣余额）。
     *
     * 直连测试：
     * 正常：POST http://localhost:8082/orders/seata/create?userId=1&amount=100
     * 回滚：POST
     * http://localhost:8082/orders/seata/create?userId=1&amount=100&forceFail=true
     */
    @PostMapping("/seata/create")
    public Result<Map<String, Object>> seataCreate(
            @NotNull(message = "userId 不能为空") @RequestParam Long userId,
            @RequestParam(defaultValue = "Spring Cloud 学习手册") String product,
            @NotNull(message = "amount 不能为空") @DecimalMin(value = "0.01", message = "下单金额必须大于 0") @RequestParam BigDecimal amount,
            @RequestParam(defaultValue = "false") boolean forceFail) {

        Long orderId = orderService.createOrder(userId, product, amount, forceFail);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderId", orderId);
        result.put("message", "全局事务已提交（订单 + 扣款均生效）");
        return Result.success(result);
    }

    /**
     * 故障接口：每次调用稳定抛异常，用于演示 Sentinel「异常比例熔断」。
     * 资源名为 /orders/fail，熔断规则触发后该接口不再真正执行，而是直接快速失败。
     * 异常最终由全局异常处理器兜底返回 500（Sentinel 在更外层统计异常，熔断不受影响）。
     */
    @GetMapping("/fail")
    public Result<Map<String, Object>> fail() {
        log.warn("故障接口被调用，准备抛出异常");
        throw new RuntimeException("模拟业务异常（订单服务内部故障）");
    }

    /**
     * 慢响应接口：不报错、正常返回，但耗时约 300ms。
     * 用于演示 Sentinel「慢调用比例熔断」——下游变慢但尚未失败时提前熔断。
     */
    @GetMapping("/slow")
    public Result<Map<String, Object>> slow() throws InterruptedException {
        log.info("慢响应接口被调用，等待 300ms");
        Thread.sleep(300);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("source", "order-service:8082");
        return Result.success(result);
    }

    /**
     * RocketMQ 事务消息演示接口：下单（事务消息保证最终一致）。
     *
     * 与 /orders/seata/create 的区别：
     * - Seata AT：强一致，本地事务 + 远程扣款同步执行，失败立即回滚
     * - 事务消息：最终一致，本地写订单后发消息，消息确认后消费者才可见
     *
     * 直连测试：
     * 正常：POST http://localhost:8082/orders/rocketmq/create?userId=1&amount=50
     * 回查：POST
     * http://localhost:8082/orders/rocketmq/create?userId=1&amount=50&forceUnknown=true
     */
    @PostMapping("/rocketmq/create")
    public Result<Map<String, Object>> rocketmqCreate(
            @NotNull(message = "userId 不能为空") @RequestParam Long userId,
            @RequestParam(defaultValue = "RocketMQ 事务消息演示") String product,
            @NotNull(message = "amount 不能为空") @DecimalMin(value = "0.01", message = "下单金额必须大于 0") @RequestParam BigDecimal amount,
            @RequestParam(defaultValue = "false") boolean forceUnknown) {

        orderTxService.createOrderWithTxMessage(userId, product, amount, forceUnknown);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", forceUnknown
                ? "半消息已发送，本地事务返回 UNKNOWN，等待 Broker 回查"
                : "半消息已发送，本地事务已提交，消费者即将收到消息");
        return Result.success(result);
    }
}
