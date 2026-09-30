package com.study.cloud.user.dto;

/**
 * 登录请求体。
 * 用 record 定义不可变 DTO，Jackson 自动把 JSON 反序列化成对象。
 */
public record LoginRequest(String username, String password) {
}
