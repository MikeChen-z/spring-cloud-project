package com.study.cloud.order.client;

import com.study.cloud.order.dto.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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
}
