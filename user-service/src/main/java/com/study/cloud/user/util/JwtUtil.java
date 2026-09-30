package com.study.cloud.user.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：负责签发与解析令牌。
 *
 * 契约要点（必须与 gateway-service 完全一致）：
 *   - 算法：HS256（对称密钥，签发和验签用同一个 secret）
 *   - 密钥：jwt.secret，长度必须 >= 32 字节
 *   - 载荷：subject 存用户 id，自定义 claim "name" 存用户名
 *   - 过期：jwt.expiration（毫秒）
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:3600000}")
    private long expiration;

    private SecretKey key;

    @PostConstruct
    public void init() {
        // 用密钥字符串的字节构造 HMAC-SHA 密钥
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 token。
     */
    public String generate(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))   // sub：用户 id
                .claim("name", username)           // 自定义 claim：用户名
                .issuedAt(now)                     // iat：签发时间
                .expiration(new Date(now.getTime() + expiration))  // exp：过期时间
                .signWith(key)                     // 用 HS256 + 密钥签名
                .compact();
    }

    /**
     * 解析并验签。签名错误或已过期会抛 JwtException，由调用方捕获。
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
