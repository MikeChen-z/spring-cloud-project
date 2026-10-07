package com.study.cloud.common.result;

import lombok.Getter;

/**
 * 统一返回状态码。
 *
 * 这里的 code 刻意与 HTTP 状态码对齐（200/400/401/500），好处是：
 * - 给前端看：和浏览器/网关的状态语义一致，直观易懂
 * - 给服务间 Feign 调用看：业务失败仍然带着 4xx/5xx 的 HTTP 状态，
 *   Feign 会据此抛异常，从而能触发 Seata 回滚 / Sentinel 降级，
 *   不会出现"HTTP 200 但业务失败"导致下游误判成功的问题
 *
 * 真实大型项目常把"HTTP 状态码"和"业务错误码"拆开（如业务码 10001、20003），
 * 学习工程先用对齐方案降低理解成本。
 */
@Getter
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "成功"),
    /** 参数校验失败（@Valid / @Validated 拦截） */
    PARAM_ERROR(400, "参数校验失败"),
    /** 业务规则不满足（如余额不足、用户不存在），语义上也是客户端请求有问题，用 400 */
    BUSINESS_ERROR(400, "业务处理失败"),
    /** 未认证 / 登录已过期 */
    UNAUTHORIZED(401, "未认证或登录已过期"),
    /** 已认证但无权限 */
    FORBIDDEN(403, "没有访问权限"),
    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),
    /** 服务端未预期异常 */
    SYSTEM_ERROR(500, "系统繁忙，请稍后再试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
