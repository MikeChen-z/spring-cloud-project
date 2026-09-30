package com.study.cloud.order.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 调用的 trace 上下文手动传播。
 *
 * 背景：开启 feign.sentinel.enabled=true 后，Sentinel 替换了 Feign 的内部调用处理器，
 * 绕过了框架自动给 Feign 套的"追踪包装"，导致发往 user-service 的请求不带 trace header，
 * user 侧每次都新建独立 trace，调用链断裂（Zipkin 里 order 和 user 永远是两条独立记录）。
 *
 * 修复：RequestInterceptor 在每次 Feign 发请求前被回调，
 * 这里从当前线程的 trace 上下文取出 traceId/spanId，手动注入 HTTP Header：
 * - traceparent ：W3C 标准格式（Spring Boot 3 默认产生/消费的格式）
 * - X-B3-TraceId ：Brave 的 B3 格式（兼容老版消费方）
 * user-service 收到请求后由其追踪组件自动解析这些 header，认领同一个 trace，
 * 从而恢复 order -> user 的调用链串联。
 */
@Configuration
public class FeignTraceConfig {

    @Bean
    public RequestInterceptor traceContextPropagation(Tracer tracer) {
        return (RequestTemplate template) -> {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan == null) {
                // 当前线程没有 trace 上下文（非追踪请求），不注入
                return;
            }
            TraceContext ctx = currentSpan.context();
            String traceId = ctx.traceId(); // 32位十六进制
            String spanId = ctx.spanId(); // 16位十六进制

            // W3C Trace Context 格式：版本-父traceId-父spanId-标志
            template.header("traceparent", "00-" + traceId + "-" + spanId + "-01");
            // B3 多头格式
            template.header("X-B3-TraceId", traceId);
            template.header("X-B3-SpanId", spanId);
        };
    }
}
