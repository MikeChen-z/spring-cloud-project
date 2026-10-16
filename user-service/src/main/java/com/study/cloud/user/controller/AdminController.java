package com.study.cloud.user.controller;

import com.study.cloud.common.result.Result;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员接口：只有 ADMIN 角色可访问（由网关 PermissionGlobalFilter 拦截）。
 *
 * 网关路由 /api/admin/** → user-service（StripPrefix=1）→ /admin/**
 * 网关权限规则 /api/admin/** → [ADMIN]
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;

    /**
     * 获取所有用户列表（仅管理员）。
     * 网关已校验角色，下游直接信任 X-User-Role 头。
     */
    @GetMapping("/users")
    public Result<List<User>> listUsers(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return Result.success(userService.list());
    }
}