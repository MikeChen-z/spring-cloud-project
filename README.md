# Spring Cloud Study —— 微服务最小闭环学习项目

以「登录 → 鉴权 → 跨服务下单」为主线，亲手打通 Spring Cloud 微服务的完整骨架：
**注册发现、配置中心、网关鉴权、声明式调用、两级缓存、熔断降级、链路追踪**。

## 一、技术栈与版本

版本必须严格对齐，错配会出现各种启动/兼容问题：

| 组件 | 版本 |
|------|------|
| JDK | 17 |
| Spring Boot | 3.2.5 |
| Spring Cloud | 2023.0.1 |
| Spring Cloud Alibaba | 2023.0.1.0 |
| Nacos | 2.x（8848 HTTP / 9848 gRPC） |
| MyBatis-Plus | 随 Boot 父工程管理 |
| JJWT | 0.12.6 |
| Caffeine / Redis | Caffeine 本地缓存 + Redis 分布式缓存 |
| Micrometer Tracing + Zipkin | 全链路追踪 |

## 二、系统架构

```
 Postman / 浏览器
       │
       ▼
┌──────────────────────────────────────────────┐
│ gateway-service :9000                        │
│  JWT 全局过滤器：白名单放行 → 验签 → 注入      │
│  X-User-Id / X-User-Name 请求头              │
└───────┬───────────────────────┬──────────────┘
        │ /api/login            │ /api/orders/**
        │ /api/users/**         │
        ▼                       ▼
┌─────────────────┐   ┌─────────────────────────┐
│ user-service    │   │ order-service :8082     │
│ :8081           │ ◄─│  OpenFeign（带 Sentinel  │
│ 登录/查用户/     │   │  降级兜底）+ @Cacheable  │
│ 配置刷新演示     │   │  Caffeine/Redis 缓存     │
└─────────────────┘   └─────────────────────────┘

基础设施：Nacos :8848 ｜ MySQL :3306 ｜ Redis :6379 ｜ Zipkin :9411
```

## 三、模块说明

| 模块 | 端口 | 职责 |
|------|------|------|
| `gateway-service` | 9000 | 统一入口。路由转发（`StripPrefix=1` 剥离 `/api`）、JWT 验签、白名单、用户身份头注入 |
| `user-service` | 8081 | 登录签发 JWT、用户查询（MySQL + MyBatis-Plus）、Nacos 配置动态刷新演示 |
| `order-service` | 8082 | 模拟订单；通过 OpenFeign 调 user-service，结果走 Caffeine/Redis 缓存，故障时 FallbackFactory 兜底 |
| `sql/init.sql` | — | 建库建表 + 初始数据 |

## 四、启动顺序

**1. 基础设施**

```bash
# Nacos：必须单机模式（cluster 模式无配置会启动失败）
startup.cmd -m standalone

# MySQL：导入初始化数据
mysql -u root -p < sql/init.sql

# Redis：默认 6379 端口

# 可选：Zipkin 链路追踪
java -jar zipkin-server.jar
```

**2. 微服务（按依赖顺序）**

user-service(8081) → order-service(8082) → gateway-service(9000)

```bash
mvn clean package
java -jar user-service/target/user-service-1.0.0.jar
java -jar order-service/target/order-service-1.0.0.jar
java -jar gateway-service/target/gateway-service-1.0.0.jar
```

> 注意：修改 `application.yml` 后必须重新 `mvn clean package`，否则跑的还是旧配置。

## 五、接口清单（统一走网关 9000）

测试账号：`张三` / `李四` / `王五`，密码均为 `123456`。

| # | 方法与路径 | 鉴权 | 说明 |
|---|-----------|------|------|
| 1 | `POST /api/login` | 白名单 | 入参 `{"username":"张三","password":"123456"}`，返回扁平结构 `token` / `userId` / `username` |
| 2 | `GET /api/users/{id}` | 需要 Token | 返回用户信息，含 `source: user-service:8081` |
| 3 | `GET /api/config-demo` | 需要 Token | Nacos 配置动态刷新演示 |
| 4 | `GET /api/orders/{id}` | 需要 Token | 完整调用链；返回 `order`、`user`、`currentUser`（当前登录用户） |

Postman 推荐用法：登录接口 Tests 写 `pm.environment.set("token", pm.response.json().token);` 自动存令牌，其余请求 Authorization 用 `Bearer Token = {{token}}`。

## 六、功能验证要点

1. **鉴权对照**：不带 Token 访问白名单外接口 → 401；带合法 Token → 200
2. **头注入**：`/api/orders/1` 返回的 `currentUser` 为登录用户名（中文经 URL 编解码透传）
3. **缓存生效**：连续两次请求订单接口，第二次 user-service 控制台不打印 SQL；Redis 中可见 `orderCache::users::1`
4. **配置刷新**：Nacos 控制台修改 `user-service.yaml` 的 `user.greeting` 并发布，不重启服务再请求即可见新值
5. **熔断降级**：停掉 user-service 后请求订单接口仍返回 200，`user` 为兜底数据（`降级用户` / `fallback@example.com`）
6. **链路追踪**：http://localhost:9411 查看 gateway → order → user 完整瀑布图、耗时与依赖拓扑

> 测降级前先清缓存：`del orderCache::users::1`，否则缓存命中不会发出 Feign 请求，降级被缓存"掩盖"。

## 七、踩坑记录

- Nacos 必须 `startup.cmd -m standalone`；客户端同时连 8848(HTTP) 和 9848(gRPC)
- Gateway 是 WebFlux，**不能引入** `spring-boot-starter-web`
- Feign + Sentinel 降级需配置 `feign.sentinel.enabled: true`；降级优先用 `FallbackFactory`（可拿到失败原因）
- YAML 列表注入（如网关白名单）必须用 `@ConfigurationProperties`，`@Value` 无法绑定 List
- user-service 与 gateway-service 的 `jwt.secret` 必须完全一致，否则合法 Token 全部验签失败
- Redis 缓存：key 带统一前缀（`orderCache::`）、显式配 TTL、缓存对象实现 `Serializable`
- `@Cacheable` 默认会缓存兜底数据，需用 `unless` SpEL 排除降级结果
- Feign/LoadBalancer 冷启动首次请求明显更慢（约 600ms vs 热身后 20~120ms），分析性能时应剔除
- Windows 下运行中的 jar 会锁文件，删包/重新打包前先停服务
- Sentinel 熔断器开启后会保持 open/half-open 30~60 秒，重试前先等恢复
