package com.study.cloud.common.result;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一返回体。所有 Controller（无论正常还是异常）最终都返回这个结构：
 * 
 * <pre>
 * { "code": 200, "message": "成功", "data": { ... } }
 * </pre>
 *
 * - code：业务状态码，见 {@link ResultCode}
 * - message：给人看的提示信息
 * - data：真正的业务数据，失败时通常为 null
 *
 * 用泛型 {@code <T>} 让成功返回的数据类型在编译期可见，调用方（含 Feign）不用强转。
 */
@Data
@NoArgsConstructor
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功但无返回数据（如写操作、删除） */
    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /** 成功并携带数据返回 */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 失败：使用预定义状态码的标准提示 */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /** 失败：自定义提示（如"余额不足：当前 50，尝试扣减 100"） */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 便捷判断：Feign 等调用方据此决定取 data 还是抛异常 */
    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
