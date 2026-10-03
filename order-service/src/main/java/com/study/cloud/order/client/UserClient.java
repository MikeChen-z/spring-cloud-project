package com.study.cloud.order.client;

import com.study.cloud.order.dto.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * user-service 的 Feign 客户端。
 *
 * name = "user-service"：目标服务在 Nacos 中注册的名字，
 *                        Feign + LoadBalancer 会从 Nacos 拉取实例列表并选一个发起调用，
 *                        代码里完全不写 IP 和端口。
 */
@FeignClient(name = "user-service", fallbackFactory = UserClientFallbackFactory.class)
public interface UserClient {

    /**
     * 对应 user-service 的 GET /users/{id}。
     * 注意：Feign 接口的 @PathVariable 必须显式写参数名。
     */
    @GetMapping("/users/{id}")
    UserDto getUser(@PathVariable("id") Long id);

    /**
     * 扣减用户余额，对应 user-service 的 POST /users/{id}/deduct?amount=xx。
     * Seata 全局事务的第二个分支（XID 由 Seata 自动通过请求头透传）。
     */
    @PostMapping("/users/{id}/deduct")
    Void deduct(@PathVariable("id") Long id, @RequestParam("amount") BigDecimal amount);
}
