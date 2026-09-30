package com.study.cloud.order.service;

import com.study.cloud.order.client.UserClient;
import com.study.cloud.order.dto.UserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

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
}
