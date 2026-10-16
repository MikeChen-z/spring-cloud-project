package com.study.cloud.common.web;

import com.alibaba.csp.sentinel.Tracer;
import com.study.cloud.common.exception.BusinessException;
import com.study.cloud.common.result.Result;
import com.study.cloud.common.result.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器（Spring MVC / Servlet 栈）。
 *
 * 集中收敛所有 Controller 抛出的异常，统一翻译成 {@link Result} 响应，
 * 避免把原始异常堆栈直接抛给前端，也让各类错误的 HTTP 状态码、响应结构保持一致。
 *
 * 关键点：返回 {@link ResponseEntity} 而不是直接返回 {@code Result}，
 * 因为我们要【保留真实的 HTTP 状态码】：
 * - 业务异常 / 参数错误 → 4xx
 * - 未预期异常 → 500
 * 这样下游 Feign 才能凭 4xx/5xx 感知失败（进而触发 Seata 回滚、Sentinel 降级）。
 *
 * 注意：本类依赖 spring-webmvc，只被 user/order 两个 MVC 服务扫描；
 * 响应式的 gateway-service（WebFlux）不会加载它。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：HTTP 状态码与异常携带的业务码保持一致（默认 400，登录失败可传 401）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        log.warn("【业务异常】{}", ex.getMessage());
        return ResponseEntity.status(ex.getCode())
                .body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 请求体校验失败：@Valid 标注的 @RequestBody 对象字段不满足约束。
     * 例如登录请求 {"username":"","password":""} → 400，并给出具体字段原因。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleArgNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("【参数校验失败】{}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), detail));
    }

    /**
     * 方法参数约束失败：类上 @Validated + 方法参数上的 @NotNull / @DecimalMin 等。
     * 例如 GET/POST 上的 @RequestParam、@PathVariable 不满足约束。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(this::formatViolation)
                .collect(Collectors.joining("; "));
        log.warn("【参数约束失败】{}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), detail));
    }

    /**
     * 请求体无法读取/解析：body 为空、JSON 格式错误、字段类型不匹配等。
     * 典型场景：调用方发出的 JSON 缺引号、压根没带 body → 400 而非 500。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("【请求体解析失败】{}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), "请求体格式错误或缺失，请检查 JSON 是否合法"));
    }

    /**
     * 必填请求参数缺失：@RequestParam 没有 required=false 且调用方没传。
     * 例如下单接口没传 userId → 400，而不是落到兜底 500。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        String detail = "缺少必填参数：" + ex.getParameterName();
        log.warn("【参数缺失】{}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), detail));
    }

    /**
     * 参数类型不匹配：例如 Long 类型的 userId 传了字符串 abc。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "参数类型错误：" + ex.getName() + " 应为 " + ex.getRequiredType().getSimpleName();
        log.warn("【参数类型错误】{}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), detail));
    }

    /**
     * 兼容仍在使用 ResponseStatusException 的地方：沿用其声明的状态码与原因，
     * 不改变原有的错误传播行为。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleResponseStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason();
        log.warn("【响应状态异常】status={}, reason={}", status, reason);
        return ResponseEntity.status(status).body(Result.fail(status, reason));
    }

    /**
     * 兜底：未预期的异常一律返回 500 与通用提示，
     * 详细堆栈只记日志（log.error），不暴露给调用方。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception ex) {
        // Seata 等 AOP 拦截器会把目标方法抛出的异常重新包成
        // RuntimeException("try to proceed invocation error", cause)，
        // 导致 @ExceptionHandler(BusinessException.class) 按精确类型匹配不上。
        // 沿 cause 链解包：只要根源是 BusinessException，就按业务异常返回 4xx 而非 500。
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof BusinessException business) {
                log.warn("【业务异常(解包)】{}", business.getMessage());
                return ResponseEntity.status(business.getCode())
                        .body(Result.fail(business.getCode(), business.getMessage()));
            }
            cause = cause.getCause();
        }
        // Sentinel Web MVC 适配器只在异常"传播到 Filter"时才会计数，
        // 而 @ExceptionHandler 会把异常就地吞掉，Sentinel 看不见 → "异常比例"熔断统计永远为 0。
        // 官方推荐做法：在异常处理器里手动上报。当前无 Sentinel 上下文时为空操作，无副作用。
        Tracer.trace(ex);
        log.error("【系统异常】未预期的错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.SYSTEM_ERROR));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private String formatViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
