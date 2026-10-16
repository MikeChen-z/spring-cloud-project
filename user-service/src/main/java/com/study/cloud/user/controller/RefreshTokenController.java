package com.study.cloud.user.controller;

import com.study.cloud.common.exception.BusinessException;
import com.study.cloud.common.result.Result;
import com.study.cloud.common.result.ResultCode;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.service.UserService;
import com.study.cloud.user.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Token 刷新接口：用 refresh token 换取新的 access token。
 *
 * 调用方式（经过网关，在白名单中无需 access token）：
 *   POST http://localhost:9000/api/refresh
 *   {"refreshToken":"<refresh token>"}
 *
 * 流程：
 *   1. 用 JwtUtil 验签 refresh token（签名+过期）
 *   2. Redis 校验 refresh:{userId} 值匹配（未被登出删除）
 *   3. 从 DB 查 user 获取最新 role（角色变更后刷新即生效）
 *   4. 签发新 access token
 */
@RestController
@RequiredArgsConstructor
public class RefreshTokenController {

    private final JwtUtil jwtUtil;
    private final UserService userService;

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshRequest request) {
        // 1+2. 验签 + Redis 校验
        Long userId = jwtUtil.validateRefreshToken(request.refreshToken());
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "刷新令牌无效或已失效");
        }

        // 3. 从 DB 获取最新角色
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在");
        }

        // 4. 签发新 access token
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getName(), user.getRole());

        return Result.success(Map.of(
                "accessToken", accessToken,
                "userId", user.getId(),
                "username", user.getName(),
                "role", user.getRole()
        ));
    }

    public record RefreshRequest(String refreshToken) {}
}