package com.study.cloud.user.controller;

import com.study.cloud.common.result.Result;
import com.study.cloud.user.dto.LoginRequest;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.service.UserService;
import com.study.cloud.user.util.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录接口：校验账号密码，通过后签发 JWT 双令牌。
 *
 * 调用方式（经过网关）：
 *   POST http://localhost:9000/api/login
 *   {"username":"张三","password":"123456"}
 *
 * 返回：
 *   accessToken  - 短时效(30min)，用于业务接口鉴权
 *   refreshToken - 长时效(7d)，用于刷新 access token
 *   userId, username, role
 *
 * 该接口在网关白名单中，无需携带 token 即可访问。
 */
@RestController
@RequiredArgsConstructor
public class LoginController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.login(request.username(), request.password());

        // 签发双令牌：access token 携带 role，refresh token 存 Redis
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getName(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getName());

        return Result.success(Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "userId", user.getId(),
                "username", user.getName(),
                "role", user.getRole()
        ));
    }
}