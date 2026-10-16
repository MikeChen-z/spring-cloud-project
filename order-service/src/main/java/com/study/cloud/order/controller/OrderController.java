package com.study.cloud.order.controller;

import com.study.cloud.common.result.Result;
import com.study.cloud.order.dto.UserDto;
import com.study.cloud.order.entity.Order;
import com.study.cloud.order.service.IdempotentTokenService;
import com.study.cloud.order.service.OrderService;
import com.study.cloud.order.service.OrderTxService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单接口：演示一次请求中跨服务调用。
 * 调用链：浏览器 -> Gateway(验签+注入用户头) -> order-service -> (缓存/Feign) -> user-service
 *
 * 所有接口统一返回 {@link Result}。
 * 类上的 @Validated 用于开启【方法参数】约束校验，
 * 配合 @RequestParam 上的 @NotNull / @DecimalMin，非法参数在进方法前就被拦截返回 400。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderTxService orderTxService;
    private final IdempotentTokenService idempotentTokenService;
    /**
     * 重试演示用的共享计数器。
     * 必须放 Redis 而不是实例内 AtomicInteger：order-service 是双副本，
     * 网关重试会经负载均衡落到不同实例，本地计数会各算各的导致演示行为不可预测。
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 当前实例的主机名。Docker 容器中即容器 ID 前缀，
     * 多实例部署时响应里带上它，可以直观看到请求被负载均衡到了哪个实例。
     */
    private static final String HOSTNAME;
    static {
        String name;
        try {
            name = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            name = "unknown-host";
        }
        HOSTNAME = name;
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(
            @PathVariable Long id,
            // 网关注入的登录用户信息；required=false 是为了兼容直连 8082 调试
            @RequestHeader(value = "X-User-Id", required = false) String currentUserId,
            @RequestHeader(value = "X-User-Name", required = false) String encodedUserName) {

        // 网关对中文用户名做了 URL 编码，这里解码还原
        String currentUserName = encodedUserName == null
                ? null
                : URLDecoder.decode(encodedUserName, StandardCharsets.UTF_8);
        log.info("当前登录用户：id={}, name={}", currentUserId, currentUserName);

        // 模拟查本地订单
        Map<String, Object> order = Map.of(
                "orderId", id,
                "product", "Spring Cloud 学习手册",
                "amount", 99.0,
                "source", "order-service:8082");

        // 查用户（带缓存）：命中时此方法不执行 Feign 调用
        UserDto user = orderService.getUserWithCache(id);

        // 用 LinkedHashMap 以便容纳可能为 null 的 currentUser（Map.of 不允许 null 值）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("user", user);
        result.put("currentUser", currentUserName == null ? "匿名（未经过网关/未登录）" : currentUserName);
        // 处理本次请求的实例标识：多副本时连续调用可观察负载均衡轮询
        result.put("serverInstance", HOSTNAME);
        return Result.success(result);
    }

    /**
     * 写接口（创建订单）：仅用于演示 Sentinel「关联模式」。
     * 当此写接口 QPS 达到阈值时，关联规则会让读接口 GET:/orders/{id} 被限流。
     */
    @PostMapping
    public Result<Map<String, Object>> create() {
        log.info("创建订单（写接口）被调用");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("source", "order-service:8082");
        return Result.success(result);
    }

    /**
     * Seata 分布式事务演示接口：下单（写订单库 + 远程扣余额）。
     *
     * 直连测试：
     * 正常：POST http://localhost:8082/orders/seata/create?userId=1&amount=100
     * 回滚：POST
     * http://localhost:8082/orders/seata/create?userId=1&amount=100&forceFail=true
     */
    @PostMapping("/seata/create")
    public Result<Map<String, Object>> seataCreate(
            @NotNull(message = "userId 不能为空") @RequestParam Long userId,
            @RequestParam(defaultValue = "Spring Cloud 学习手册") String product,
            @NotNull(message = "amount 不能为空") @DecimalMin(value = "0.01", message = "下单金额必须大于 0") @RequestParam BigDecimal amount,
            @RequestParam(defaultValue = "false") boolean forceFail) {

        Long orderId = orderService.createOrder(userId, product, amount, forceFail);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderId", orderId);
        result.put("message", "全局事务已提交（订单 + 扣款均生效）");
        return Result.success(result);
    }

    /**
     * 幂等第一步：获取一次性防重令牌。
     * 前端进入下单页时调用，提交订单时放到 Idempotent-Token 请求头。
     *
     * 经网关：GET http://localhost:9000/api/orders/idempotent/token（需登录）
     */
    @GetMapping("/idempotent/token")
    public Result<Map<String, Object>> idempotentToken(
            @RequestHeader(value = "X-User-Id", required = false) String currentUserId) {
        String token = idempotentTokenService.issueToken(currentUserId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("header", "Idempotent-Token");
        result.put("expiresInSeconds", 300);
        return Result.success(result);
    }

    /**
     * 幂等第二步：携带令牌下单（业务逻辑与 /seata/create 完全一致）。
     *
     * 同一令牌：第一次 200 正常下单，第二次 400「请勿重复提交」，
     * 余额只扣一次、订单只建一条。
     *
     * 经网关：POST
     * http://localhost:9000/api/orders/seata/create-idem?userId=1&amount=100
     * 请求头：Idempotent-Token: <第一步拿到的 token>
     */
    @PostMapping("/seata/create-idem")
    public Result<Map<String, Object>> seataCreateIdempotent(
            @RequestHeader(value = "Idempotent-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String currentUserId,
            @NotNull(message = "userId 不能为空") @RequestParam Long userId,
            @RequestParam(defaultValue = "幂等下单演示") String product,
            @NotNull(message = "amount 不能为空") @DecimalMin(value = "0.01", message = "下单金额必须大于 0") @RequestParam BigDecimal amount,
            @RequestParam(defaultValue = "false") boolean forceFail) {

        // 先原子消费令牌：拿不到（重复/缺失/盗用）直接抛异常，后面的业务一行都不执行
        idempotentTokenService.consumeOrThrow(token, currentUserId);

        Long orderId = orderService.createOrder(userId, product, amount, forceFail);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderId", orderId);
        result.put("message", "幂等下单成功（令牌已销毁，重复提交将被拒绝）");
        return Result.success(result);
    }

    /**
     * 故障接口：每次调用稳定抛异常，用于演示 Sentinel「异常比例熔断」。
     * 资源名为 /orders/fail，熔断规则触发后该接口不再真正执行，而是直接快速失败。
     * 异常最终由全局异常处理器兜底返回 500（被处理的异常 Sentinel 拦截器看不到，需 Tracer.trace 显式上报）。
     */
    @GetMapping("/fail")
    public Result<Map<String, Object>> fail() {
        log.warn("故障接口被调用，准备抛出异常");
        RuntimeException ex = new RuntimeException("模拟业务异常（订单服务内部故障）");
        com.alibaba.csp.sentinel.Tracer.trace(ex); // 全局异常处理器会把异常消化成 500 响应，Sentinel 拦截器看不到，必须显式上报，否则异常比例熔断永远不触发
        throw ex;
    }

    /**
     * 慢响应接口：不报错、正常返回，但耗时约 300ms。
     * 用于演示 Sentinel「慢调用比例熔断」——下游变慢但尚未失败时提前熔断。
     */
    @GetMapping("/slow")
    public Result<Map<String, Object>> slow() throws InterruptedException {
        log.info("慢响应接口被调用，等待 300ms");
        Thread.sleep(300);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("source", "order-service:8082");
        return Result.success(result);
    }

    /**
     * 超时触发接口：故意 sleep 5s，远超网关 order-service 路由配置的 2s 超时。
     * 用于演示 Gateway 路由级超时：客户端应在 ~2s 收到 504 Gateway Timeout，
     * 而不是干等 5s；本方法即使最终返回也到达不了客户端。
     *
     * 注意：Tomcat 默认 connectionTimeout=20s，本接口本身不会被容器中断，
     * 真正"提前断开"的是网关侧的 response-timeout。
     */
    @GetMapping("/timeout")
    public Result<Map<String, Object>> timeout() throws InterruptedException {
        log.info("超时触发接口被调用，将 sleep 5s（网关 2s 后会先返回 504）");
        Thread.sleep(5000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("source", "order-service:8082");
        return Result.success(result);
    }

    /** 重试演示：常规模式的调用序号（INCR 原子自增，双副本共享） */
    private static final String RETRY_COUNTER_KEY = "retry:demo:counter";
    /** 重试演示：耗尽模式的调用序号，用于证明后端实际被调用次数 */
    private static final String RETRY_ALWAYS_FAIL_KEY = "retry:demo:always-fail";

    /**
     * 网关 Retry 演示接口（配合网关 order 路由的 Retry filter：retries=2，共 3 次尝试）。
     *
     * 常规模式（无参）：全局第 1、2 次调用返回真实 503，第 3 次返回 200。
     * 客户端只发【一次】请求 → 网关内部经历 503→重试→503→重试→200，最终客户端看到 200，
     * 响应体 attempts=3 即后端实际被调用 3 次的证据。
     *
     * 耗尽模式（?alwaysFail=true）：每次都返回真实 503。
     * 网关重试 2 次仍全部失败后，把最后一次的 503 透传给客户端；
     * Redis 里 always-fail 计数 = 3，证明重试有上限、不会无限放大流量。
     *
     * 必须抛 ResponseStatusException：GlobalExceptionHandler 会保留真实 HTTP 503，
     * 网关 Retry 是按 HTTP 状态码（5xx）判断的；若被吞成 HTTP 200，重试永远不会触发。
     */
    @GetMapping("/retry-demo")
    public Result<Map<String, Object>> retryDemo(
            @RequestParam(defaultValue = "false") boolean alwaysFail) {

        String key = alwaysFail ? RETRY_ALWAYS_FAIL_KEY : RETRY_COUNTER_KEY;
        long attempt = redisTemplate.opsForValue().increment(key);
        log.info("重试演示接口被调用：alwaysFail={}，后端第 {} 次尝试，实例={}", alwaysFail, attempt, HOSTNAME);

        if (alwaysFail) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "模拟持续不可用：第 " + attempt + " 次尝试仍 503");
        }

        // 前 2 次（attempt % 3 = 1、2）抛 503；第 3 次（整除）成功。可重复演示：3、6、9... 次成功
        if (attempt % 3 != 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "模拟瞬时故障：第 " + attempt + " 次返回 503，等待网关重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("attempts", attempt);
        result.put("serverInstance", HOSTNAME);
        result.put("message", "前两次 503 已被网关 Retry 消化，调用方只看到这一次 200");
        return Result.success(result);
    }

    /** 熔断演示：Redis 计数 key（双副本共享，必须用 Redis 而非实例内变量） */
    private static final String CIRCUIT_TEST_KEY = "circuit:test";

    /**
     * 网关 CircuitBreaker 熔断演示接口（配合网关 order 路由的 CircuitBreaker filter）。
     *
     * 用 Redis 计数控制前 failAfter 次成功、之后持续 503，
     * 触发熔断器 closed→open→half-open→closed 完整循环。
     *
     * 熔断器参数（见 gateway application.yml resilience4j 配置）：
     * slidingWindowSize=10, minimumNumberOfCalls=10, failureRateThreshold=50%,
     * waitDurationInOpenState=10s, permittedNumberOfCallsInHalfOpenState=3
     *
     * 演示流程（配合 verify 脚本第 12 章）：
     * 1. 重置 Redis 计数=0，连续调用 10 次：前 4 次 200，后 6 次 503
     * → 10 次窗口 6 次失败=60% ≥ 50% → 熔断器 OPEN
     * 2. 再调用 → 网关直接走 fallback（毫秒级返回，不打后端）
     * 3. 等 10s（waitDurationInOpenState）→ 自动转 HALF_OPEN
     * 4. 重置 Redis 计数=0（让后端"恢复"），调用 3 次（半开探测配额）→ 全 200 → CLOSED
     *
     * 必须用 ResponseStatusException(503)：保留真实 HTTP 503，网关 CircuitBreaker
     * 按 statusCodes:503 判定为失败计入熔断统计（被 GlobalExceptionHandler 吞成 200 就不会触发）。
     */
    @GetMapping("/circuit-test")
    public Result<Map<String, Object>> circuitTest(
            @RequestParam(defaultValue = "4") int failAfter) {
        long attempt = redisTemplate.opsForValue().increment(CIRCUIT_TEST_KEY);
        log.info("熔断演示接口被调用：attempt={}，failAfter={}，实例={}", attempt, failAfter, HOSTNAME);

        if (attempt > failAfter) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "模拟持续故障：第 " + attempt + " 次返回 503（>" + failAfter + "），触发熔断器统计失败");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("attempt", attempt);
        result.put("serverInstance", HOSTNAME);
        result.put("message", "第 " + attempt + " 次正常返回（≤ failAfter=" + failAfter + "）");
        return Result.success(result);
    }

    /**
     * RocketMQ 事务消息演示接口：下单（事务消息保证最终一致）。
     *
     * 与 /orders/seata/create 的区别：
     * - Seata AT：强一致，本地事务 + 远程扣款同步执行，失败立即回滚
     * - 事务消息：最终一致，本地写订单后发消息，消息确认后消费者才可见
     *
     * 直连测试：
     * 正常：POST http://localhost:8082/orders/rocketmq/create?userId=1&amount=50
     * 回查：POST
     * http://localhost:8082/orders/rocketmq/create?userId=1&amount=50&forceUnknown=true
     */
    @PostMapping("/rocketmq/create")
    public Result<Map<String, Object>> rocketmqCreate(
            @NotNull(message = "userId 不能为空") @RequestParam Long userId,
            @RequestParam(defaultValue = "RocketMQ 事务消息演示") String product,
            @NotNull(message = "amount 不能为空") @DecimalMin(value = "0.01", message = "下单金额必须大于 0") @RequestParam BigDecimal amount,
            @RequestParam(defaultValue = "false") boolean forceUnknown) {

        orderTxService.createOrderWithTxMessage(userId, product, amount, forceUnknown);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", forceUnknown
                ? "半消息已发送，本地事务返回 UNKNOWN，等待 Broker 回查"
                : "半消息已发送，本地事务已提交，消费者即将收到消息");
        return Result.success(result);
    }

    /**
     * 模拟支付接口：在延迟消息到期前调用，把订单状态改为已支付。
     * 延迟消费者收到消息时看到 status=1，就不会自动取消。
     *
     * 经网关：POST http://localhost:9000/api/orders/{id}/pay
     */
    @PostMapping("/{id}/pay")
    public Result<Map<String, Object>> pay(
            @PathVariable Long id) {
        orderService.payOrder(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderId", id);
        result.put("message", "订单已支付，延迟消息检查时会跳过取消");
        return Result.success(result);
    }

    /**
     * 查询订单状态（含延迟消息演示的完整状态流转）。
     * status: 0=未支付 1=已支付 2=已取消
     *
     * 经网关：GET http://localhost:9000/api/orders/{id}/status
     */
    @GetMapping("/{id}/status")
    public Result<Map<String, Object>> status(@PathVariable Long id) {
        Order order = orderService.getOrderById(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("status", order.getStatus());
        String desc = switch (order.getStatus()) {
            case 0 -> "未支付（等待延迟消息检查）";
            case 1 -> "已支付";
            case 2 -> "已取消（延迟消息自动取消或手动取消）";
            default -> "未知状态";
        };
        result.put("statusDesc", desc);
        result.put("createTime", order.getCreateTime());
        return Result.success(result);
    }

    /**
     * 【仅测试用】手动触发取消，扮演"延迟消息到期时的取消动作"。
     * 用于并发竞态测试：与 /{id}/pay 同时发起，验证 CAS 条件更新能否防止
     * "支付成功却被取消"的丢失更新。生产环境不应暴露此接口。
     *
     * 经网关：POST http://localhost:9000/api/orders/{id}/admin-cancel
     */
    @PostMapping("/{id}/admin-cancel")
    public Result<Map<String, Object>> adminCancel(@PathVariable Long id) {
        orderService.cancelOrder(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderId", id);
        result.put("message", "订单已取消（测试接口，模拟延迟消息到期）");
        return Result.success(result);
    }
}
