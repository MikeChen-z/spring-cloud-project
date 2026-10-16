package com.study.cloud.order.service;

import com.study.cloud.common.exception.BusinessException;
import com.study.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 接口幂等：防重令牌（Token）机制。
 *
 * 适用场景：下单这类"重复提交会造成资损"的写接口——用户快速点两次、
 * 网络抖动客户端超时重试、网关重试，都只应生效一次。
 *
 * 流程：
 * 1. 进入下单页时先请求令牌：issueToken → 生成 UUID 存入 Redis（带 5 分钟 TTL）
 * 2. 提交订单时在请求头 Idempotent-Token 带上令牌
 * 3. 服务端"校验并销毁"令牌，必须原子完成：销毁成功才执行业务
 * → 同一令牌的第二次请求拿不到（已删除），直接拒绝
 *
 * 为什么用 Lua 脚本而不是 get 后再 delete？
 * "先 GET 判断存在、再 DEL"是两条命令，高并发下两个请求可能都 GET 成功
 * （都还没 DEL），于是都放行。Redis 单线程执行 Lua 脚本，
 * 比对 + 删除在一个脚本内原子完成，中间不会插入其他命令。
 *
 * 令牌的 value 存"签发用户"：消费时与当前登录用户比对，
 * 防止 A 用户的令牌被 B 用户盗用（即使拿到 UUID 也用不了）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentTokenService {

    /** Redis key 前缀，最终形如 idem:seata:9f3a...（冒号是 Redis 推荐的命名分隔） */
    private static final String KEY_PREFIX = "idem:seata:";

    /** 令牌有效期：下单页停留太久令牌自动作废，防止废弃令牌长期占用内存 */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** 不经网关直连调试时没有 X-User-Id 头，用统一的匿名身份（学习用，生产应强制登录） */
    private static final String ANONYMOUS = "anonymous";

    private final StringRedisTemplate redisTemplate;

    /**
     * 原子"比对 + 删除"脚本：
     * - 令牌存在且属于当前用户 → 删除并返回 1（放行）
     * - 令牌不存在 / 已被用过 / 归属不符 → 返回 0（拒绝）
     * KEYS[1] = 令牌 key，ARGV[1] = 当前用户 id
     */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    /**
     * 签发令牌。
     *
     * @param currentUserId 网关注入的登录用户 id（X-User-Id），直连时为 null
     * @return 一次性令牌（UUID）
     */
    public String issueToken(String currentUserId) {
        String owner = currentUserId == null ? ANONYMOUS : currentUserId;
        String token = UUID.randomUUID().toString().replace("-", "");
        // SET key value NX（不存在才设置，语义上更严谨）EX 300（秒）
        redisTemplate.opsForValue().set(KEY_PREFIX + token, owner, TTL);
        log.info("【幂等】签发令牌 token={}, owner={}", token, owner);
        return token;
    }

    /**
     * 消费令牌：原子校验并销毁，失败抛业务异常（400），调用方业务不会执行。
     *
     * @param token         请求头 Idempotent-Token
     * @param currentUserId 当前登录用户 id
     */
    public void consumeOrThrow(String token, String currentUserId) {
        if (token == null || token.isBlank()) {
            // 没带头由我们抛友好提示，而不是让框架抛 MissingRequestHeaderException
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "缺少幂等令牌请求头 Idempotent-Token，请先 GET /orders/idempotent/token 获取");
        }
        String owner = currentUserId == null ? ANONYMOUS : currentUserId;
        Long deleted = redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(KEY_PREFIX + token),
                owner);
        if (deleted == null || deleted == 0L) {
            log.warn("【幂等】拒绝重复/非法提交 token={}, owner={}", token, owner);
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "请勿重复提交：该令牌已使用、不存在或不属于当前用户（令牌 5 分钟内一次性有效）");
        }
        log.info("【幂等】令牌消费成功 token={}", token);
    }
}
