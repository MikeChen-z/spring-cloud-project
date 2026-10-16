package com.study.cloud.user.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT 工具类：负责签发与解析令牌。
 *
 * 双令牌机制：
 *   - Access Token（短时效 30min）：用于业务接口鉴权，携带 role claim
 *   - Refresh Token（长时效 7d）：用于刷新 access token，存 Redis 可主动吊销
 *
 * 契约要点（必须与 gateway-service 完全一致）：
 *   - 算法：HS256（对称密钥，签发和验签用同一个 secret）
 *   - 密钥：jwt.secret，长度必须 >= 32 字节
 *   - 载荷：subject 存用户 id，自定义 claim "name" 存用户名
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-expiration:1800000}")
    private long accessExpiration;

    @Value("${jwt.refresh-expiration:604800000}")
    private long refreshExpiration;

    private final StringRedisTemplate redisTemplate;

    private SecretKey key;

    public JwtUtil(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 Access Token（短时效，携带 role）。
     */
    public String generateAccessToken(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("name", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpiration))
                .signWith(key)
                .compact();
    }

    /**
     * 签发 Refresh Token（长时效，不携带 role）。
     * 同时存入 Redis：key=refresh:{userId}，value=token，TTL=7天。
     * 刷新时校验 Redis 中的值是否匹配，实现主动吊销。
     */
    public String generateRefreshToken(Long userId, String username) {
        Date now = new Date();
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("name", username)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpiration))
                .signWith(key)
                .compact();

        // 存 Redis，key=refresh:{userId}，用于刷新时校验 + 登出时删除
        String redisKey = "refresh:" + userId;
        redisTemplate.opsForValue().set(redisKey, token, refreshExpiration, TimeUnit.MILLISECONDS);

        return token;
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

    /**
     * 校验 refresh token 是否在 Redis 中有效（未被登出删除）。
     * @return userId 如果校验通过，null 如果 token 不匹配或不存在
     */
    public Long validateRefreshToken(String refreshToken) {
        try {
            Claims claims = parse(refreshToken);
            Long userId = Long.parseLong(claims.getSubject());
            String redisKey = "refresh:" + userId;
            String stored = redisTemplate.opsForValue().get(redisKey);
            if (stored != null && stored.equals(refreshToken)) {
                return userId;
            }
        } catch (Exception e) {
            log.warn("Refresh token 校验失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 删除 refresh token（登出时调用）。
     */
    public void removeRefreshToken(Long userId) {
        redisTemplate.delete("refresh:" + userId);
    }

    /**
     * 将 access token 加入黑名单（登出时调用）。
     * key=blacklist:{token}，TTL=token 剩余有效期，过期后自动清理。
     */
    public void blacklistAccessToken(String token, long remainingMillis) {
        if (remainingMillis > 0) {
            redisTemplate.opsForValue().set("blacklist:" + token, "1",
                    remainingMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 获取 access token 的剩余有效时长（毫秒）。
     */
    public long getRemainingExpiration(String token) {
        try {
            Claims claims = parse(token);
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
            return Math.max(0, remaining);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从 token 中解析 userId。
     */
    public Long getUserIdFromToken(String token) {
        try {
            return Long.parseLong(parse(token).getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 token 中解析用户名。
     */
    public String getUsernameFromToken(String token) {
        try {
            return parse(token).get("name", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 token 中解析角色。
     */
    public String getRoleFromToken(String token) {
        try {
            return parse(token).get("role", String.class);
        } catch (Exception e) {
            return null;
        }
    }
}