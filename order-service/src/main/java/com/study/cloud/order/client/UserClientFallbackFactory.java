package com.study.cloud.order.client;

import com.study.cloud.order.dto.UserDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * user-service 的降级兜底工厂。
 *
 * 当 user-service 不可用（宕机、网络不通、超时）时，Feign 调用会抛异常，
 * Sentinel 捕获后转而调用这里的 create() 方法返回的"替身"实现，
 * 从而让 order-service 不至于跟着崩溃。
 *
 * 用 FallbackFactory 而非直接写 fallback 类的好处：
 *   能拿到 cause（失败原因），可以打日志区分是超时还是连接拒绝。
 */
@Component
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {

    private static final Logger log = LoggerFactory.getLogger(UserClientFallbackFactory.class);

    @Override
    public UserClient create(Throwable cause) {
        // 打印失败原因，方便排查（生产环境可改为更结构化的告警）
        log.warn("调用 user-service 失败，触发降级。原因：{}", cause.getMessage());

        return new UserClient() {
            /**
             * 兜底逻辑：返回一个"降级用户"对象。
             * 真实项目里可能是：查本地缓存、返回默认值、抛业务异常等。
             */
            @Override
            public UserDto getUser(Long id) {
                return new UserDto(
                        id,
                        "降级用户",
                        "fallback@example.com",
                        "来自降级兜底（user-service 不可用）"
                );
            }

            /**
             * 扣款是写操作，绝不能静默降级：必须把异常抛出去，
             * 否则 @GlobalTransactional 感知不到分支失败，订单分支会错误提交。
             */
            @Override
            public Void deduct(Long id, java.math.BigDecimal amount) {
                throw new RuntimeException("扣款失败（user-service 不可用），全局事务必须回滚。原因：" + cause.getMessage());
            }
        };
    }
}
