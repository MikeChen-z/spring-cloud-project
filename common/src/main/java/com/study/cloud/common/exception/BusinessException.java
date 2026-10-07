package com.study.cloud.common.exception;

import com.study.cloud.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * Service 层在"业务规则不满足"时抛出它，而不是：
 * - 返回 null（调用方容易忘记判空）
 * - 直接抛 RuntimeException（语义不清，全局处理器无法区分业务失败和系统故障）
 *
 * 全局异常处理器会捕获它，并把 {@link #code} 同时作为 HTTP 状态码和响应体业务码返回。
 * 默认 code = 400（{@link ResultCode#BUSINESS_ERROR}），
 * 需要其它状态码（如登录失败 401）时用带 {@link ResultCode} 的构造方法。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    /** 默认按 400 业务错误处理 */
    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    /** 使用预定义状态码 + 标准提示 */
    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    /** 使用预定义状态码 + 自定义提示（如登录失败固定 401，但提示语可定制） */
    public BusinessException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
