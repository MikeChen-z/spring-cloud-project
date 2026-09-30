package com.study.cloud.order.controller;

import com.study.cloud.order.dto.UserDto;
import com.study.cloud.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
