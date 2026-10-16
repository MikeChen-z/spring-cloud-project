package com.study.cloud.gateway.filter;

import com.study.cloud.gateway.config.AuthProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 全局 JWT 鉴权过滤器。
 *
 * 职责：白名单放行 → 取 token → 验签 → 黑名单检查 → 透传用户身份+角色。
 *
 * 鉴权链路：
 *   1. 白名单路径（登录、刷新接口）直接放行
 *   2. 取 Authorization 头，要求 Bearer 前缀
 *   3. 验签 + 过期校验
 *   4. Redis 黑名单检查（登出后 token 主动失效）
 *   5. 透传 X-User-Id / X-User-Name / X-User-Role 到下游服务
 */
@Slf4j
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${jwt.secret}")
    private String secret;

    private final AuthProperties authProperties;
    private final ReactiveStringRedisTemplate redisTemplate;

    private SecretKey key;

    public JwtAuthGlobalFilter(AuthProperties authProperties,
                               ReactiveStringRedisTemplate redisTemplate) {
        this.authProperties = authProperties;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 白名单放行
        if (authProperties.getWhiteList().stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        // 2. 取 token
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return reject(exchange, "缺少令牌或格式错误，需 Authorization: Bearer <token>");
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        // 3. 验签 + 过期校验
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.warn("JWT 校验失败 path={} reason={}", path, e.getMessage());
            return reject(exchange, "令牌无效或已过期");
        }

        // 4. Redis 黑名单检查（登出后 token 主动失效）
        String blacklistKey = "blacklist:" + token;
        return redisTemplate.hasKey(blacklistKey)
                .defaultIfEmpty(false)
                .flatMap(inBlacklist -> {
                    if (Boolean.TRUE.equals(inBlacklist)) {
                        log.warn("令牌在黑名单中 path={}", path);
                        return reject(exchange, "令牌已失效，请重新登录");
                    }

                    // 5. 解析用户身份 + 角色，透传到下游
                    String userId = claims.getSubject();
                    String username = claims.get("name", String.class);
                    String role = claims.get("role", String.class);
                    String encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8);

                    ServerHttpRequest mutated = request.mutate()
                            .header("X-User-Id", userId)
                            .header("X-User-Name", encodedName)
                            .header("X-User-Role", role != null ? role : "USER")
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
    }

    /** 返回 401 JSON 响应，中断请求 */
    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}