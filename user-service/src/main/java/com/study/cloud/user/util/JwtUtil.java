package com.study.cloud.user.util;

import com.study.cloud.common.exception.BusinessException;
import com.study.cloud.common.result.ResultCode;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JWT 工具类：负责签发与解析令牌。
 *
 * 双令牌机制：
 * - Access Token（短时效 30min）：用于业务接口鉴权，携带 role claim
 * - Refresh Token（长时效 7d）：用于刷新 access token，存 Redis 且【一次一换】。
 * 每次刷新都签发新值、作废旧值；已被作废的旧值再次出现即判定令牌泄露，吊销整条刷新链
 *
 * 契约要点（必须与 gateway-service 完全一致）：
 * - 算法：HS256（对称密钥，签发和验签用同一个 secret）
 * - 密钥：jwt.secret，长度必须 >= 32 字节
 * - 载荷：subject 存用户 id，自定义 claim "name" 存用户名
 */
@Slf4j
@Component
public class JwtUtil {

    /** 已被轮转掉的 refresh token 留档 key 前缀（refresh:rotated:{userId}），仅用于重放检测 */
    private static final String ROTATED_KEY_PREFIX = "refresh:rotated:";

    /**
     * 重放检测窗口：被轮转令牌的留档时长。
     * 足够捕获"同一 refresh token 被使用两次"，又不会长期占用 Redis 内存。
     */
    private static final long ROTATED_KEEP_MILLIS = 300_000L;

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
     * 由 {@link #rotateRefreshToken} 调用：新值直接覆盖该 key，旧值随即失效。
     */
    public String generateRefreshToken(Long userId, String username) {
        Date now = new Date();
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("name", username)
                .claim("type", "refresh")
                // jti：iat/exp 只精确到秒，同一秒内两次签发会产出【完全相同】的 token，
                // 那样"轮转"形同虚设（新值 == 旧值）。加随机 jti 保证每次签发都是新令牌。
                .id(UUID.randomUUID().toString().replace("-", ""))
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
     * 轮转 refresh token：校验旧令牌 → 签发新令牌覆盖 Redis 记录 → 旧令牌立即失效。
     *
     * 每个 refresh token 只能用一次：
     * - 正常：命中当前有效值 → 签发新令牌，并把旧令牌留档用于重放检测
     * - 重放：命中的是"已轮转留档"的值 → 判定令牌泄露，吊销该用户整条刷新链
     *
     * "重放即视为泄露"是 OAuth2 安全最佳实践：同一 refresh token 被使用两次时，
     * 无法区分攻击者与合法用户，只能两端一起作废、强制重新登录。
     *
     * @param oldToken 客户端持有的 refresh token
     * @return 新签发的 refresh token（客户端必须覆盖本地存储）
     * @throws BusinessException 缺失、非法/过期、已失效，或检测到重放
     */
    public String rotateRefreshToken(String oldToken) {
        if (oldToken == null || oldToken.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "缺少刷新令牌");
        }

        Claims claims;
        try {
            claims = parse(oldToken);
        } catch (Exception e) {
            log.warn("Refresh token 验签失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.UNAUTHORIZED, "刷新令牌无效或已过期");
        }
        Long userId = Long.parseLong(claims.getSubject());
        String currentKey = "refresh:" + userId;
        String rotatedKey = ROTATED_KEY_PREFIX + userId;

        String stored = redisTemplate.opsForValue().get(currentKey);
        if (stored != null && stored.equals(oldToken)) {
            // 正常轮转：先签发新令牌（内部覆盖 currentKey），再把旧令牌留档用于重放检测
            String newToken = generateRefreshToken(userId, claims.get("name", String.class));
            redisTemplate.opsForValue().set(rotatedKey, oldToken, ROTATED_KEEP_MILLIS, TimeUnit.MILLISECONDS);
            log.info("【令牌轮转】userId={} 已签发新 refresh token，旧令牌立即失效", userId);
            return newToken;
        }

        // 未命中当前有效值。若命中"已轮转留档"，说明旧令牌被重复使用 → 按泄露处置
        String rotated = redisTemplate.opsForValue().get(rotatedKey);
        if (rotated != null && rotated.equals(oldToken)) {
            redisTemplate.delete(currentKey);
            redisTemplate.delete(rotatedKey);
            log.error("【安全告警】检测到 refresh token 重放 userId={}，判定令牌已泄露，已吊销该用户刷新链，需重新登录", userId);
            throw new BusinessException(ResultCode.UNAUTHORIZED, "检测到令牌重放，会话已吊销，请重新登录");
        }

        throw new BusinessException(ResultCode.UNAUTHORIZED, "刷新令牌无效或已失效");
    }

    /**
     * 删除 refresh token（登出时调用）。
     * 同时清掉重放检测留档，避免登出后旧令牌被误判为"重放"。
     */
    public void removeRefreshToken(Long userId) {
        redisTemplate.delete("refresh:" + userId);
        redisTemplate.delete(ROTATED_KEY_PREFIX + userId);
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