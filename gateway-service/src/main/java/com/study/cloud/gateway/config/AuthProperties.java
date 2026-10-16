package com.study.cloud.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 鉴权配置。
 * - whiteList: 白名单路径前缀（不需要 JWT 校验）
 * - permissionRules: RBAC 权限规则列表，每条规则包含路径模式和允许的角色
 *
 * 注意：不能用 Map<String, List<String>> 存规则，因为 Spring Boot relaxed binding
 * 会把 key 中的 / 和 * 去掉（/api/admin/** → apiadmin）。
 * 用 List<PermissionRule> 对象，pattern 作为属性值不被规范化。
 */
@Component
@ConfigurationProperties(prefix = "auth")
@Data
public class AuthProperties {
    private List<String> whiteList;
    private List<PermissionRule> permissionRules;

    @Data
    public static class PermissionRule {
        private String pattern;
        private List<String> roles;
    }
}