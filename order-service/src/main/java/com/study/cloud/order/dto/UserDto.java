package com.study.cloud.order.dto;

import java.io.Serializable;

/**
 * user-service 返回的用户数据结构。
 * 用 record 定义不可变 DTO，Jackson 可直接将 JSON 反序列化进来。
 * 字段名与 user-service 返回的 JSON key 保持一致。
 * 实现 Serializable 以支持 Redis JDK 序列化。
 */
public record UserDto(Long id, String name, String email, String source) implements Serializable {
    private static final long serialVersionUID = 1L;
}
