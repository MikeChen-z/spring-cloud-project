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
 * Token 刷新接口：用 refresh token 换取新的 access token，并【轮转】refresh token。
 *
 * 调用方式（经过网关，在白名单中无需 access token）：
 *   POST http://localhost:9000/api/refresh
 *   {"refreshToken":"<refresh token>"}
 *
 * 流程：
 *   1. 轮转校验：验签 + 过期 + Redis 值匹配 → 签发新 refresh token（旧值立即失效）
 *      - 若提交的是"已被轮转掉的旧令牌" → 判定重放（令牌可能已泄露），吊销该用户整条刷新链
 *   2. 从 DB 查 user 获取最新 role（角色变更后刷新即生效）
 *   3. 签发新 access token
 *
 * 客户端契约：响应中的 refreshToken 必须覆盖本地存储，继续用旧值下次会被判重放。
 */
@RestController
@RequiredArgsConstructor
public class RefreshTokenController {

    private final JwtUtil jwtUtil;
    private final UserService userService;

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshRequest request) {
        // 1. 轮转：校验旧令牌并签发新 refresh token（旧值立即失效；重放则吊销整条链）
        String newRefreshToken = jwtUtil.rotateRefreshToken(request.refreshToken());
        Long userId = jwtUtil.getUserIdFromToken(newRefreshToken);

        // 2. 从 DB 获取最新角色
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在");
        }

        // 3. 签发新 access token
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getName(), user.getRole());

        return Result.success(Map.of(
                "accessToken", accessToken,
                // 轮转后的新 refresh token，客户端必须覆盖本地旧值
                "refreshToken", newRefreshToken,
                "userId", user.getId(),
                "username", user.getName(),
                "role", user.getRole()
        ));
    }

    public record RefreshRequest(String refreshToken) {}
}