package com.study.cloud.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体。
 * 用 record 定义不可变 DTO，Jackson 自动把 JSON 反序列化成对象。
 *
 * 字段上的 @NotBlank 是 Bean Validation 约束：
 * Controller 在参数前加 @Valid 后，进入方法前会自动校验，
 * 不满足则抛 MethodArgumentNotValidException，由全局异常处理器统一返回 400，
 * 不会带着空用户名/空密码进入业务逻辑（也省掉了手写 if 判空）。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password) {
}
