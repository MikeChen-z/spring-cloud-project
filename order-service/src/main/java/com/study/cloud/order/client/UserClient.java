package com.study.cloud.order.client;

import com.study.cloud.common.result.Result;
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
     *
     * 返回类型是 Result&lt;UserDto&gt;：Feign 本质是 HTTP 调用，
     * 拿到的也是统一返回体，由调用方判断 code 后取 data（Spring 解码器会按泛型还原 UserDto）。
     */
    @GetMapping("/users/{id}")
    Result<UserDto> getUser(@PathVariable("id") Long id);

    /**
     * 扣减用户余额，对应 user-service 的 POST /users/{id}/deduct?amount=xx。
     * Seata 全局事务的第二个分支（XID 由 Seata 自动通过请求头透传）。
     *
     * 这里返回类型保持 Void：扣款成败靠 HTTP 状态码区分（业务失败 user 返回 4xx，
     * Feign 直接抛异常 → fallback 再抛 → 全局回滚），无需解析响应体。
     */
    @PostMapping("/users/{id}/deduct")
    Void deduct(@PathVariable("id") Long id, @RequestParam("amount") BigDecimal amount);
}
