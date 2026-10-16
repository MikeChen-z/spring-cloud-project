package com.study.cloud.gateway.controller;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关本地降级端点：CircuitBreaker filter 配了 fallbackUri 后，会 forward 到这里。
 *
 * 进入这里有四种触发原因，必须按异常类型区分 HTTP 语义，不能一律 503：
 * 1. CallNotPermittedException —— 熔断器 OPEN，请求未到达下游，返回 503。
 * 2. 超时类异常 —— 网关 response-timeout(2s) 先触发，返回 504 GATEWAY_TIMEOUT。
 * 3. HttpStatusCodeException —— 响应码命中 filter 的 statusCodes(500/503)：
 *    这是下游【真实返回的错误状态码】，必须原样透传（如 Seata 回滚的 500），
 *    不能被降级响应吞掉，否则业务方拿不到正确的状态码。
 * 4. 其它下游异常 —— 统一 503。
 *
 * 触发异常由 Spring Cloud Gateway 放入 exchange 属性
 * {@link ServerWebExchangeUtils#CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR}。
 */
@RestController
public class FallbackController {

    // 不能用 @GetMapping：fallbackUri 的 forward 保留原始 HTTP 方法，
    // POST 下单（如 Seata 回滚返回 500）也会 forward 成 POST，限定 GET 会直接 405。
    @RequestMapping("/fallback/order")
    public Mono<ResponseEntity<Map<String, Object>>> orderFallback(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        Throwable ex = exchange.getAttribute(
                ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);

        boolean circuitOpen = findCause(ex, CallNotPermittedException.class) != null;
        boolean timeout = typeNameContainsTimeout(ex);
        HttpStatusCodeException statusEx = findCause(ex, HttpStatusCodeException.class);

        HttpStatus status;
        String reason;
        String message;
        boolean fallback = true;

        if (circuitOpen) {
            status = HttpStatus.SERVICE_UNAVAILABLE; // 503：熔断打开，请求未到达下游
            reason = "CIRCUIT_OPEN";
            message = "order-service 熔断打开：网关快速失败（未请求下游），请稍后重试";
        } else if (timeout) {
            status = HttpStatus.GATEWAY_TIMEOUT;     // 504：下游在 response-timeout 内未返回
            reason = "RESPONSE_TIMEOUT";
            message = "order-service 响应超时（网关 response-timeout=2s），返回 504";
        } else if (statusEx != null) {
            // 下游真实错误码（命中 statusCodes）：原样透传，不改成统一降级码
            HttpStatusCode sc = statusEx.getStatusCode();
            status = HttpStatus.resolve(sc.value()) != null
                    ? HttpStatus.resolve(sc.value()) : HttpStatus.SERVICE_UNAVAILABLE;
            reason = "DOWNSTREAM_STATUS";
            fallback = false; // 这是下游状态码透传，不是熔断主动降级
            message = "下游返回错误状态码 " + sc.value() + "，网关原样透传";
        } else {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            reason = "DOWNSTREAM_ERROR";
            message = "order-service 暂不可用，网关返回降级响应，请稍后重试";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        body.put("data", null);
        body.put("path", path);
        body.put("fallback", fallback);
        body.put("fallbackReason", reason);
        return Mono.just(ResponseEntity.status(status).body(body));
    }

    /** 在异常 cause 链中查找指定类型的异常（找不到返回 null） */
    private <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) { // 自引用保护
                break;
            }
        }
        return null;
    }

    /**
     * 判断异常链中是否为超时：覆盖 JUC TimeoutException、Netty ReadTimeoutException、
     * Spring ResponseTimeoutException 等（简单类名均含 Timeout）。
     */
    private boolean typeNameContainsTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
