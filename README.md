# Spring Cloud Study —— 微服务最小闭环学习项目

以「登录 → 鉴权 → 跨服务下单」为主线，亲手打通 Spring Cloud 微服务的完整骨架：
**注册发现、配置中心、网关鉴权、声明式调用、两级缓存、熔断降级、流控限流、链路追踪**。

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
| Sentinel | 1.8.8（流控/熔断，核心在依赖中） |
| Sentinel Dashboard | 1.8.8（独立控制台，:8858，可选可视化） |
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
│                 │   │  Sentinel 流控（限流）   │
└─────────────────┘   └─────────────────────────┘

基础设施：Nacos :8848 ｜ MySQL :3306 ｜ Redis :6379 ｜ Zipkin :9411 ｜ Sentinel Dashboard :8858
```

## 三、模块说明

| 模块 | 端口 | 职责 |
|------|------|------|
| `gateway-service` | 9000 | 统一入口。路由转发（`StripPrefix=1` 剥离 `/api`）、JWT 验签、白名单、用户身份头注入 |
| `user-service` | 8081 | 登录签发 JWT、用户查询（MySQL + MyBatis-Plus）、Nacos 配置动态刷新演示 |
| `order-service` | 8082 | 模拟订单；通过 OpenFeign 调 user-service，结果走 Caffeine/Redis 缓存，故障时 FallbackFactory 兜底；Sentinel 流控规则持久化到 Nacos |
| `sql/init.sql` | — | 建库建表 + 初始数据 |

## 四、启动顺序

**1. 基础设施**

```bash
# Nacos：必须单机模式（cluster 模式无配置会启动失败）
startup.cmd -m standalone

# MySQL：导入初始化数据
mysql -u root -p < sql/init.sql

# Redis：默认 6379 端口

# Sentinel Dashboard：独立控制台（可选，账号/密码均为 sentinel）
java "-Dserver.port=8858" -jar sentinel-dashboard-1.8.8.jar

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
| 5 | `POST /api/orders` | 需要 Token | 创建订单（写接口），用于演示关联流控模式 |
| 6 | `GET /api/orders/fail` | 需要 Token | 稳定报错接口，用于演示异常比例熔断 |

Postman 推荐用法：登录接口 Tests 写 `pm.environment.set("token", pm.response.json().token);` 自动存令牌，其余请求 Authorization 用 `Bearer Token = {{token}}`。

## 六、功能验证要点

1. **鉴权对照**：不带 Token 访问白名单外接口 → 401；带合法 Token → 200
2. **头注入**：`/api/orders/1` 返回的 `currentUser` 为登录用户名（中文经 URL 编解码透传）
3. **缓存生效**：连续两次请求订单接口，第二次 user-service 控制台不打印 SQL；Redis 中可见 `orderCache::users::1`
4. **配置刷新**：Nacos 控制台修改 `user-service.yaml` 的 `user.greeting` 并发布，不重启服务再请求即可见新值
5. **熔断降级**：停掉 user-service 后请求订单接口仍返回 200，`user` 为兜底数据（`降级用户` / `fallback@example.com`）
6. **链路追踪**：http://localhost:9411 查看 gateway → order → user 完整瀑布图、耗时与依赖拓扑
7. **流控限流**：对订单接口瞬间打并发请求，超过 QPS 阈值的请求返回 429（详见 7.3~7.5）
8. **异常比例熔断**：连续请求 `/orders/fail`，先见真实 500、跳闸后变 429 拦截，5 秒周期反复探测（详见 7.6）

> 测降级前先清缓存：`del orderCache::users::1`，否则缓存命中不会发出 Feign 请求，降级被缓存"掩盖"。

## 七、Sentinel 流控规则

### 7.1 核心概念与架构

- **资源（resource）**：被保护的 URL / 方法。Spring Web 的 URL、OpenFeign 调用会被**自动埋点**为资源，无需手写。
- **限流核心在依赖里**：有 `spring-cloud-starter-alibaba-sentinel` 就能统计 QPS、执行限流，**不装 Dashboard 也能用**。
- **Dashboard 是独立进程**，只负责可视化与临时改规则；默认把规则存在**内存**，Dashboard 或服务**重启即丢**。

```
Nacos（order-service-flow-rules.json）  ← 规则唯一事实源：持久 + 版本 + 审计
   │ 启动拉取 / 发布推送
   ▼
服务 FlowRuleManager（执行限流判断）──心跳/上报──▶ Dashboard（监控 + 应急临时改）
```

### 7.2 规则字段

| 维度 | 取值 |
|------|------|
| 阈值类型 | QPS（每秒请求数）/ 并发线程数（同时在执行的线程数） |
| 流控模式 | 直接 / 关联 |
| 流控效果 | 快速失败 / Warm Up（预热）/ 排队等待 |

### 7.3 三种流控效果（实测对比）

| 效果 | 配置 | 10 个并发实测 | 适用场景 |
|------|------|--------------|---------|
| 快速失败 | QPS=2 | **2 通过 / 8 拒绝(429)** | 最常用的兜底保护 |
| Warm Up | count=9，预热 10s | 每秒通过数 **3 → 9 爬升** | 保护冷启动/刚扩容的服务 |
| 排队等待 | QPS=2，超时 2000ms | **10 个全成功**，每个延迟约 500ms | 削峰，请求不能丢 |

要点：

- **Warm Up**：初始阈值 = count ÷ 冷因子(3) = 9÷3 = 3，随持续流量在 10 秒内爬升到 9。预热必须靠**持续不断**的流量驱动，停顿令牌桶会"回冷"。
- **排队等待**：匀速器把请求严格排成每 `1000÷QPS = 500ms` 放行一个；等待超过「超时时间」才 429。超时设太小（如 10ms）会退化成快速失败。
- 手动点 Send 很难触发限流（手速 + 等响应），验证需用脚本/压测工具瞬间打满。

### 7.4 关联模式（流控模式 = 关联）

规则配在**被牺牲的资源**上，通过「关联资源」盯住要保护的核心资源：

```
规则配在读接口 GET /orders/{id}，关联资源 = POST /orders，阈值=2
当【写接口】QPS ≥ 2 → 【读接口】被限流（429），写接口本身不限
```

实测：基线只发读 4 个 → 全通过；每轮先打 4 个写、再发 4 个读 → 写全通过、**读 4 个全 429**。典型用途：**保写弃读、保核心链路弃边缘功能**（写库更耗资源，让连接池/CPU 给写入）。

### 7.5 规则持久化到 Nacos（生产做法）

**1）pom 增加数据源**

```xml
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
</dependency>
```

**2）application.yml 配置数据源**

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: 127.0.0.1:8858
      datasource:
        flow:
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: ${spring.application.name}-flow-rules.json
            group-id: SENTINEL_GROUP
            data-type: json
            rule-type: flow
```

**3）Nacos 配置**：Data ID `order-service-flow-rules.json`，Group `SENTINEL_GROUP`，内容为规则数组：

```json
[
  { "resource": "/orders/{id}", "limitApp": "default", "grade": 1,
    "count": 2, "strategy": 0, "controlBehavior": 0, "clusterMode": false }
]
```

**4）实测验证**

- 重启服务规则自动回来：8 并发 → **2 通过 / 6 拒绝**；
- Nacos 把 count 改 2→5 发布（**不重启**）→ **5 通过 / 3 拒绝**，热更新生效；
- Dashboard 规则列表中外部数据源规则的 `gmtCreate/gmtModified` 为 `null`，可与手工规则区分。

> 注意真实资源名：本项目为 `/orders/{id}` 与 `/orders`（无 `GET:`/`POST:` 前缀）；而 Feign 出站资源是 `GET:http://user-service/users/{id}`。配置前先在 Dashboard「簇点链路」核对。

### 7.6 熔断降级规则（异常比例实测）

熔断与限流互补：**限流看请求量（预防），熔断看下游健康（止损）**。熔断器是三态状态机：

```
CLOSED（关闭＝通路，放行并统计）
   │ 统计指标达阈值 → 跳闸
   ▼
OPEN（断开，全部快速失败，持续「熔断时长」给下游喘息）
   │ 熔断时长结束
   ▼
HALF_OPEN（半开，只放 1 个探测请求）
   ├─ 探测成功 → CLOSED（清零恢复）
   └─ 探测失败 → 重新 OPEN（再等一轮）
```

**Dashboard 新增熔断规则**（异常比例策略）：

| 字段 | 值 | 含义 |
|------|-----|------|
| 资源名 | `/orders/fail` | 稳定抛异常的故障接口 |
| 熔断策略 | 异常比例 | 另两种：慢调用比例 / 异常数 |
| 比例阈值 | 0.5 | 窗口内异常占比 ≥ 50% 跳闸 |
| 熔断时长 | 2s | OPEN 持续时间 |
| 最小请求数 | 5 | 窗口请求不足 5 个不触发，防偶发误判 |
| 统计时长 | 1000ms | 滑动统计窗口 |

**分阶段实测**（每批并发，观察状态机）：

| 时刻 | 状态 | 结果 |
|------|------|------|
| t=0s | CLOSED → 跳闸 | **8 × 500**，请求真实执行；异常比例 100% > 50% |
| t=1s | OPEN | **5 × 429**，方法体 0 执行 |
| t=4s | HALF_OPEN | **1 × 500 + 4 × 429**，探测失败 → 重新 OPEN |
| t=7s | HALF_OPEN | 同上，周期振荡 |

三重证据：响应码（500→429）、服务日志（方法只执行 8+2 次，被拦 13 次未进方法）、Zipkin 耗时（真实请求 ~187ms 标红 vs 拦截请求 ~10ms）。

三种熔断策略对比：

| 策略 | 触发条件 | 场景 |
|------|---------|------|
| 慢调用比例 | RT > 最大 RT 的请求占比超阈值 | 下游变慢但还没报错，最灵敏的预警 |
| 异常比例 | 异常数 / 总请求 ≥ 阈值 | 下游抛异常、500 增多 |
| 异常数 | 窗口内异常数 ≥ 绝对次数 | 低流量接口，比例算不出来时用绝对数 |

要点：

- **下游不恢复，熔断器永远回不到 CLOSED**：故障接口永远报错时，系统在 OPEN ↔ HALF_OPEN 间无限振荡，是正常行为。
- 429 响应体默认文案 `Blocked by Sentinel (flow limiting)` 是所有 `BlockException`（含熔断 `DegradeException`）的统一处理，文字写"flow limiting"但实为熔断；生产可自定义 `BlockExceptionHandler` 返回结构化提示。
- 熔断规则同样可像 7.5 那样用 Nacos 数据源持久化（`rule-type: degrade`）。

## 八、踩坑记录

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
- Sentinel Dashboard 规则默认只存内存，Dashboard 或服务重启即丢 → 生产用 Nacos 数据源持久化
- 服务在 Dashboard 不出现时，可加 `-Dcsp.sentinel.heartbeat.client.ip=127.0.0.1`；Dashboard 登录会话会过期，调接口返回 401 需重新登录
- 测流控要保证请求"同时到达且落在单个统计窗口"：业务慢（冷缓存真调 DB）会让单轮跨窗口、QPS 数据失真
- 排队等待的「超时时间」单位是毫秒，设太小（如 10ms）会退化成快速失败；阈值也不要设太小，否则 Warm Up 爬升看不出来
- 熔断跳闸后请求不再返回真实 500，而是 429 + `Blocked by Sentinel`；此响应 ~10ms（真实 500 ~187ms），可据此确认方法未执行
- 故障不消除时熔断器在 OPEN ↔ HALF_OPEN 间振荡，不会自行回 CLOSED；想重置可删除并重建规则
- Dashboard 熔断规则接口为 `/degrade/rules.json`（基础路径 `/degrade`，不是 `/v2/degrade`）
