package com.study.cloud.order.controller;

import com.study.cloud.order.dto.UserDto;
import com.study.cloud.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单接口：演示一次请求中跨服务调用。
 * 调用链：浏览器 -> Gateway(验签+注入用户头) -> order-service -> (缓存/Feign) -> user-service
 *
 * 改造：调用户这步改为走 OrderService.getUserWithCache，
 * 命中缓存时不会发出 Feign 请求，减轻 user-service 压力。
 */
@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/{id}")
    public Map<String, Object> getById(
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
        return result;
    }

    /**
     * 写接口（创建订单）：仅用于演示 Sentinel「关联模式」。
     * 当此写接口 QPS 达到阈值时，关联规则会让读接口 GET:/orders/{id} 被限流。
     */
    @PostMapping
    public Map<String, Object> create() {
        log.info("创建订单（写接口）被调用");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("source", "order-service:8082");
        return result;
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
    public Map<String, Object> seataCreate(
            @org.springframework.web.bind.annotation.RequestParam Long userId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "Spring Cloud 学习手册") String product,
            @org.springframework.web.bind.annotation.RequestParam java.math.BigDecimal amount,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean forceFail) {

        Long orderId = orderService.createOrder(userId, product, amount, forceFail);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderId", orderId);
        result.put("message", "全局事务已提交（订单 + 扣款均生效）");
        return result;
    }

    /**
     * 故障接口：每次调用稳定抛异常，用于演示 Sentinel「异常比例熔断」。
     * 资源名为 /orders/fail，熔断规则触发后该接口不再真正执行，而是直接快速失败。
     */
    @GetMapping("/fail")
    public Map<String, Object> fail() {
        log.warn("故障接口被调用，准备抛出异常");
        throw new RuntimeException("模拟业务异常（订单服务内部故障）");
    }

    /**
     * 慢响应接口：不报错、正常返回，但耗时约 300ms。
     * 用于演示 Sentinel「慢调用比例熔断」——下游变慢但尚未失败时提前熔断。
     */
    @GetMapping("/slow")
    public Map<String, Object> slow() throws InterruptedException {
        log.info("慢响应接口被调用，等待 300ms");
        Thread.sleep(300);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("source", "order-service:8082");
        return result;
    }
}
