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
import java.util.List;

/**
 * 全局 JWT 鉴权过滤器。
 *
 * 它是整条链路的"安检门"，对每个进入网关的请求执行：
 * 1. 白名单路径（如登录接口）直接放行
 * 2. 取 Authorization 头，要求 Bearer 前缀
 * 3. 用与 user-service 相同的密钥【真正验签 + 校验过期】（不是只看 token 存不存在）
 * 4. 验签通过：把用户 id/name 放入请求头透传给下游服务
 * 5. 任何一步失败：直接返回 401，请求不会到达下游
 *
 * 安全性来自第 3 步的"验证"，而不是第 2 步的"携带"——伪造/过期的 token 过不了验签。
 */
@Slf4j
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    /** Authorization 头的固定前缀，RFC 6750 规定的 Bearer 方案 */
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${jwt.secret}")
    private String secret;

    /** 白名单路径：这些路径不需要 token（从 application.yml 读取） */
    // @Value("${auth.white-list}")
    // private List<String> whiteList;

    private final AuthProperties authProperties;

    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 白名单放行（登录、未来的注册接口等）
        if (authProperties.getWhiteList().stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        // 2. 取 token
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return reject(exchange, "缺少令牌或格式错误，需 Authorization: Bearer <token>");
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        // 3. 验签 + 过期校验（签名错误、过期、被篡改都会抛异常）
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

        // 4. 解析出用户身份，写入下游请求头（透传）
        String userId = claims.getSubject();
        String username = claims.get("name", String.class);
        // HTTP 头不建议直接放中文，按 URL 编码后传递，下游解码
        String encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8);

        ServerHttpRequest mutated = request.mutate()
                .header("X-User-Id", userId)
                .header("X-User-Name", encodedName)
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
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

    public JwtAuthGlobalFilter(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /**
     * 顺序值越小越先执行。-100 保证鉴权在路由转发和大多数内置过滤器之前完成。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
