package com.study.cloud.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 自定义限流拒绝响应。
 *
 * <p>Spring Cloud Gateway 的 RequestRateLimiter 在令牌耗尽时调用
 * response.setComplete() 直接提交空 body 的 429 响应。
 * 本过滤器用 ServerHttpResponseDecorator 重写 setComplete()，
 * 当状态码为 429 时改为 writeWith(JSON body)，返回友好的统一错误体。</p>
 */
@Slf4j
@Component
public class RateLimitResponseFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse originalResponse = exchange.getResponse();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> setComplete() {
                if (getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    String path = exchange.getRequest().getURI().getPath();
                    log.warn("请求被限流 path={} remoteAddr={}",
                            path, exchange.getRequest().getRemoteAddress());

                    String body = "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null,"
                            + "\"path\":\"" + path + "\"}";

                    if (!getHeaders().containsKey(HttpHeaders.CONTENT_TYPE)) {
                        getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    }
                    DataBuffer buffer = bufferFactory()
                            .wrap(body.getBytes(StandardCharsets.UTF_8));
                    return writeWith(Mono.just(buffer));
                }
                return super.setComplete();
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }
}