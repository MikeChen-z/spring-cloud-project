package com.study.cloud.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把本次请求的 traceId 写到响应头 X-Trace-Id。
 *
 * 背景：micrometer-tracing（Brave 桥接）在处理 HTTP 请求时会把 traceId 放进 SLF4J MDC，
 * key 固定为 "traceId"。但调用方（前端/Postman/排查同学）默认看不到它，出了问题
 * 没法报出"我这次请求的编号"。这个过滤器在请求处理完后把 MDC 中的 traceId
 * 回填到响应头，于是：
 *   1. curl/浏览器响应头里直接能看到 X-Trace-Id；
 *   2. 拿到它可以在所有服务的日志里 grep 同一个 traceId，串起整条调用链；
 *   3. 也可以用它去 Zipkin 查 /api/v2/trace/{traceId} 看完整 span。
 *
 * 注意：traceId 全链路相同、spanId 每跳不同——所以下游服务写出的 X-Trace-Id
 * 就是整条链的 traceId，网关作为代理会原样透传下游响应头给客户端。
 */
@Component
@Order(0)
public class TraceIdResponseFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // tracing 过滤器（更高优先级）在调用本过滤器前已把 traceId 放入 MDC，
        // 趁响应头还没写出、响应未提交，先把头写上（最可靠的时机）
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 兜底：个别情况下 traceId 在链内才确定（如入口处未采样/异步切换），
            // 响应未提交时补上；无论成功失败（含 4xx/5xx）都尽量让调用方拿得到
            String current = MDC.get("traceId");
            if (current != null && !current.isBlank() && !response.isCommitted()) {
                response.setHeader(TRACE_ID_HEADER, current);
            }
        }
    }
}
