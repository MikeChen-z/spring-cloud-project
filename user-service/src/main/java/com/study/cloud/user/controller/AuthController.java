package com.study.cloud.user.controller;

import com.study.cloud.common.exception.BusinessException;
import com.study.cloud.common.result.Result;
import com.study.cloud.common.result.ResultCode;
import com.study.cloud.user.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登出接口：将 access token 加入 Redis 黑名单，并删除 refresh token。
 *
 * 调用方式（经过网关，需携带有效 access token）：
 *   POST http://localhost:9000/api/logout
 *   Authorization: Bearer <access token>
 *
 * 流程：
 *   1. 从 Authorization 头取 access token
 *   2. 解析 userId + 计算剩余有效期
 *   3. Redis SET blacklist:{token} 1 EX {剩余秒数}
 *   4. Redis DEL refresh:{userId}（同时吊销刷新能力）
 *   5. 返回成功
 *
 * 登出后：
 *   - 旧 access token 在黑名单中，网关验签后检查黑名单会返回 401
 *   - 旧 refresh token 已从 Redis 删除，无法再刷新
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @PostMapping("/logout")
    public Result<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "缺少令牌");
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        // 解析 userId
        Long userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "令牌无效");
        }

        // 计算剩余有效期，加入黑名单
        long remaining = jwtUtil.getRemainingExpiration(token);
        jwtUtil.blacklistAccessToken(token, remaining);

        // 删除 refresh token（吊销刷新能力）
        jwtUtil.removeRefreshToken(userId);

        log.info("用户登出 userId={} token已加入黑名单 剩余{}ms", userId, remaining);

        return Result.success(Map.of("message", "登出成功"));
    }
}