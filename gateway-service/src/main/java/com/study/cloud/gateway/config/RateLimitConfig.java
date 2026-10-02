package com.study.cloud.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 网关限流维度配置。
 * RequestRateLimiter 必须指定 KeyResolver：决定令牌桶按什么 key 计数。
 */
@Configuration
public class RateLimitConfig {

    /**
     * 按用户限流：优先取 Authorization 头（每个登录用户一个桶），
     * 没有令牌（如登录接口）则退化为按客户端 IP。
     * Bean 名 userKeyResolver 会被 yml 中 key-resolver: "#{@userKeyResolver}" 引用。
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String token = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (token != null && !token.isBlank()) {
                return Mono.just("user:" + token.hashCode());
            }
            if (exchange.getRequest().getRemoteAddress() != null) {
                return Mono.just("ip:" + exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
            }
            return Mono.just("anonymous");
        };
    }
}
