package com.study.cloud.gateway.filter;

import com.study.cloud.gateway.config.AuthProperties;
import com.study.cloud.gateway.config.AuthProperties.PermissionRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RBAC 权限过滤器：按路径匹配角色要求。
 * order=-99（在 JwtAuthGlobalFilter -100 之后）。
 */
@Slf4j
@Component
public class PermissionGlobalFilter implements GlobalFilter, Ordered {

    private final AuthProperties authProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public PermissionGlobalFilter(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        String role = request.getHeaders().getFirst("X-User-Role");
        if (role == null) {
            return chain.filter(exchange);
        }

        List<PermissionRule> rules = authProperties.getPermissionRules();
        if (rules == null || rules.isEmpty()) {
            return chain.filter(exchange);
        }

        for (PermissionRule rule : rules) {
            if (pathMatcher.match(rule.getPattern(), path)) {
                List<String> allowedRoles = rule.getRoles();
                if (allowedRoles == null || allowedRoles.contains(role)) {
                    return chain.filter(exchange);
                }
                log.warn("权限不足 path={} role={} 需要角色={}", path, role, allowedRoles);
                return forbidden(exchange, "权限不足，需要角色：" + allowedRoles);
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":403,\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -99;
    }
}