# Spring Cloud Study —— 微服务最小闭环学习项目

以「登录 → 鉴权 → 跨服务下单」为主线，亲手打通 Spring Cloud 微服务的完整骨架：
**注册发现、配置中心、网关鉴权、声明式调用、两级缓存、熔断降级、流控限流、链路追踪、分布式事务、消息队列**。

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
| Seata | 2.0.0（AT 模式分布式事务，TC 端须用 JRE 8 启动） |
| RocketMQ | 4.9.7（NameServer :9876 / Broker :10911） |
| rocketmq-spring-boot-starter | 2.3.0（Spring Boot 3 兼容版） |

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
│ user-service    │◄──│ order-service :8082     │
│ :8081           │Feign│  OpenFeign + 缓存       │
│ 登录/查用户/     │   │  Sentinel 流控/熔断      │
│ 扣款(RM)/加积分  │   │  @GlobalTransactional(TM)│
│ (RocketMQ 消费)  │   │  RocketMQ 生产者/事务消息 │
└────────┬────────┘   └───────────┬─────────────┘
         │                        │
         ▼                        ▼
   study_cloud.t_user       order_db.t_order
         └───────────┬────────────┘
                     ▼
        ┌─────────────────────────┐   ┌──────────────────────┐
        │ Seata Server (TC) :8091 │   │ RocketMQ             │
        │ 注册到 Nacos，协调两阶段 │   │ NameServer:9876      │
        │ 提交/回滚（undo_log）    │   │ Broker:10911         │
        └─────────────────────────┘   │ order-topic/         │
                                      │ order-tx-topic       │
                                      └──────────────────────┘

基础设施：Nacos :8848 ｜ MySQL :3306 ｜ Redis :6379 ｜ Zipkin :9411
          Sentinel Dashboard :8858 ｜ Seata TC :8091/:7091 ｜ RocketMQ :9876/:10911
```

## 三、模块说明

| 模块 | 端口 | 职责 |
|------|------|------|
| `gateway-service` | 9000 | 统一入口。路由转发（`StripPrefix=1` 剥离 `/api`）、JWT 验签、白名单、用户身份头注入 |
| `user-service` | 8081 | 登录签发 JWT、用户查询、Nacos 配置动态刷新；Seata 扣款分支（RM）；RocketMQ 消费者（加积分） |
| `order-service` | 8082 | OpenFeign 调 user-service + 两级缓存 + Sentinel 流控/熔断；Seata 全局事务入口（TM）；RocketMQ 生产者/事务消息 |
| `sql/init.sql` | — | 基础库表 + 初始用户数据 |
| `sql/seata_demo.sql` | — | order_db.t_order 建表；t_user 增加 balance 字段 |
| `sql/rocketmq_demo.sql` | — | t_user 增加 points 字段 |

## 四、启动顺序

**1. 基础设施**

```bash
# Nacos：必须单机模式（cluster 模式无配置会启动失败）
startup.cmd -m standalone

# MySQL：按顺序导入
#   (1) sql/init.sql              基础库表 + 用户
#   (2) Seata Server 端 4 张表：   D:\seata\script\server\db\mysql.sql  → 独立 seata 库
#   (3) 业务库 undo_log 快照表：    D:\seata\script\client\at\db\mysql.sql → order_db、study_cloud 各执行一次
#   (4) sql/seata_demo.sql        t_order 表 + t_user.balance
#   (5) sql/rocketmq_demo.sql     t_user.points
mysql -u root -p < sql/init.sql

# Redis：默认 6379 端口

# Sentinel Dashboard：独立控制台（可选，账号/密码均为 sentinel）
java "-Dserver.port=8858" -jar sentinel-dashboard-1.8.8.jar

# 可选：Zipkin 链路追踪
java -jar zipkin-server.jar

# Seata TC（必须用 JRE 8：jraft 1.3.x 与 JDK21 不兼容；-h 固定本机 IP 防多网卡误绑）
D:\seata\start-seata-jre8.bat
# 验证：日志见 "seata server started"，Nacos 服务列表出现 seata-server，控制台 http://localhost:7091 (seata/seata)

# RocketMQ：先 NameServer 后 Broker（两个独立窗口，本机用调低内存的自定义 bat）
D:\rocketmq\start-namesrv-jre8.bat    # 见 "The Name Server boot success"
D:\rocketmq\start-broker-jre8.bat     # 见 "The broker[broker-a,127.0.0.1:10911] boot success"

# 建 Topic（本机 autoCreateTopicEnable 偶发不生效，显式创建最稳妥）
cd D:\rocketmq\bin
mqadmin.cmd updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t order-topic
mqadmin.cmd updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t order-tx-topic
```

**2. 微服务（按依赖顺序）**

user-service(8081) → order-service(8082) → gateway-service(9000)

```bash
mvn clean package
java -jar user-service/target/user-service-1.0.0.jar
java -jar order-service/target/order-service-1.0.0.jar
java -jar gateway-service/target/gateway-service-1.0.0.jar
```

启动成功的关键日志：
- order-service / user-service：`register TM success`、`register RM success`（Seata 连上 TC）
- user-service：两个 `running container ... topic='order-topic' / 'order-tx-topic'`（RocketMQ 消费者就绪）

> 注意：修改 `application.yml` 后必须重新 `mvn clean package`，否则跑的还是旧配置（IDEA 绿色三角直接启动除外）。
> 改了生产者/消费者代码要重启**对应**服务：生产者在 order-service，消费者在 user-service。

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
| 7 | `GET /api/orders/slow` | 需要 Token | 慢响应接口（~300ms），用于演示慢调用比例熔断 |
| 8 | `POST /api/orders/seata/create` | 直连 8082 测试 | Seata AT 下单：写订单 + 扣余额 + 发积分消息；`forceFail=true` 演示全局回滚 |
| 9 | `POST /api/orders/rocketmq/create` | 直连 8082 测试 | RocketMQ 事务消息下单；`forceUnknown=true` 演示 Broker 事务回查 |

> 接口 8/9 学习阶段直连 `http://localhost:8082` 调用（网关 JWT 不影响这两条链路）：
> `POST http://localhost:8082/orders/seata/create?userId=1&amount=10`

Postman 推荐用法：登录接口 Tests 写 `pm.environment.set("token", pm.response.json().token);` 自动存令牌，其余请求 Authorization 用 `Bearer Token = {{token}}`。

## 六、功能验证要点

1. **鉴权对照**：不带 Token 访问白名单外接口 → 401；带合法 Token → 200
2. **头注入**：`/api/orders/1` 返回的 `currentUser` 为登录用户名（中文经 URL 编解码透传）
3. **缓存生效**：连续两次请求订单接口，第二次 user-service 控制台不打印 SQL；Redis 中可见 `orderCache::users::1`
4. **配置刷新**：Nacos 控制台修改 `user-service.yaml` 的 `user.greeting` 并发布，不重启服务再请求即可见新值
5. **熔断降级**：停掉 user-service 后请求订单接口仍返回 200，`user` 为兜底数据（`降级用户` / `fallback@example.com`）
6. **链路追踪**：http://localhost:9411 查看 gateway → order → user 完整瀑布图、耗时与依赖拓扑
7. **流控限流**：对订单接口瞬间打并发请求，超过 QPS 阈值的请求返回 429（详见 7.3~7.5）
8. **异常比例熔断**：连续请求 `/orders/fail`，先见真实 500、跳闸后变 429 拦截，周期反复探测（详见 7.6）
9. **慢调用比例熔断**：并发请求 `/orders/slow`，请求全部 200 成功但都 ~300ms 超阈值，照样跳闸（详见 7.7）
10. **Seata 正常提交**：调 `/orders/seata/create`，t_order 新增订单、t_user.balance 减少、points 增加（消息异步）
11. **Seata 异常回滚**：加 `forceFail=true`，订单与余额改动全部回滚（undo_log 生效）；自增 ID 不回退属正常现象
12. **RocketMQ 事务消息**：普通提交后积分增加；`forceUnknown=true` 时积分不变，约 50~60s 后 Broker 回查返回 COMMIT，积分才增加（详见第十章）

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

### 7.5.1 熔断规则持久化

与流控同一套机制，在 `datasource` 下再加一个 `degrade` 数据源（`rule-type: degrade`）：

```yaml
      datasource:
        degrade:
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: ${spring.application.name}-degrade-rules.json
            group-id: SENTINEL_GROUP
            data-type: json
            rule-type: degrade
```

Nacos 配置：Data ID `order-service-degrade-rules.json`，Group `SENTINEL_GROUP`，内容即 7.6/7.7 实测的两条规则：

```json
[
  { "resource": "/orders/fail", "grade": 1, "count": 0.5,
    "timeWindow": 2, "minRequestAmount": 5, "statIntervalMs": 1000 },
  { "resource": "/orders/slow", "grade": 0, "count": 200,
    "slowRatioThreshold": 0.5, "timeWindow": 2, "minRequestAmount": 5, "statIntervalMs": 1000 }
]
```

> 熔断规则字段与 Dashboard 表单对应：`grade` 0=慢调用比例 / 1=异常比例 / 2=异常数；慢调用时 `count` 是最大 RT(ms)、另需 `slowRatioThreshold`，异常策略下 `count` 是比例/次数阈值；`timeWindow` 是熔断时长（秒）。

已验证：重启后两条熔断规则自动加载（Dashboard 可查），Nacos 改阈值 0.5→0.3 发布即热更新（无需重启）。

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
- 熔断规则已持久化到 Nacos，见 7.5.1（重启自动加载 + 热更新均已验证）。

### 7.7 慢调用比例熔断（实测）

慢调用策略关注响应**快慢**而非成败：响应 RT 超过「最大 RT」即记为一次慢调用，占比超阈值就跳闸——下游变慢但还没报错时提前止损，是最灵敏的预警型策略。

**Dashboard 熔断规则**：

| 字段 | 值 | 含义 |
|------|-----|------|
| 资源名 | `/orders/slow` | 不报错、sleep 300ms 的慢响应接口 |
| 熔断策略 | 慢调用比例 | |
| 最大 RT | `200` | 响应 > 200ms 记为慢调用（接口实际 ~300ms） |
| 比例阈值 | `0.5` | 慢调用占比 ≥ 50% 跳闸 |
| 熔断时长 / 最小请求数 / 统计时长 | 2s / 5 / 1000ms | 同 7.6 |

**并发实测**（慢请求必须并发：顺序发 1 秒窗口挤不进 5 个）：

| 时刻 | 状态 | 结果 | 批次耗时 |
|------|------|------|---------|
| t=0s | CLOSED → 跳闸 | **8 × 200**，全部成功但都慢 | 374ms |
| t=1s | OPEN | **5 × 429**，方法 0 执行 | 41ms |
| t=4s | HALF_OPEN | **1 × 200 + 4 × 429**，探测太慢 → 重新 OPEN | 313ms |
| t=7s | HALF_OPEN | 同上，周期振荡 | 327ms |

日志佐证：慢方法共执行 11 次（初始 1 + 8 + 探测 2），被拦 13 次未进方法。

与异常比例熔断的关键差异：

- **业务全部 200 成功也会跳闸**：只要 RT 超阈值、慢调用占比够高即触发，无需等报错。
- **HALF_OPEN 探测：业务成功 ≠ 探测通过**。探测请求虽返回 200，但 RT 仍 300ms > 200ms，仍判失败、重新 OPEN；异常比例则要求探测请求抛异常才重新 OPEN。
- 批次耗时本身可作证据：374ms（并发 sleep）→ 41ms（全拦截）→ 313ms（恰 1 个 sleep）。

## 八、Gateway Redis 令牌桶限流

与 Sentinel 互补：**Sentinel 在服务内部保护资源，网关在最外层按用户/IP 挡流量**。RequestRateLimiter 过滤器用 Redis 令牌桶，集群多实例共享同一份令牌计数。

**1）依赖**（网关是 WebFlux 栈，必须用 Reactive 版 Redis 客户端）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
```

**2）KeyResolver**（`RateLimitConfig.java`）：决定按什么维度计数。本例按用户：取 `Authorization` 头做 key，无令牌（如登录接口）退化为客户端 IP。

**3）路由挂过滤器**（挂在 `/api/users/**`，避开 order-service 上的 Sentinel 规则干扰）：

```yaml
- id: user-service
  uri: lb://user-service
  predicates:
    - Path=/api/users/**
  filters:
    - StripPrefix=1
    - name: RequestRateLimiter
      args:
        key-resolver: "#{@userKeyResolver}"
        redis-rate-limiter.replenishRate: 2   # 每秒补 2 个令牌
        redis-rate-limiter.burstCapacity: 4   # 桶最多攒 4 个
        redis-rate-limiter.requestedTokens: 1 # 每请求扣 1 个
```

**4）实测**（快速连发，令牌桶行为精确可算）：

| 批次 | 请求数 | 结果 | 解读 |
|------|--------|------|------|
| burst A | 8 | **200×4 + 429×4** | 满桶 4 令牌：过 4 拒 4 |
| burst B（3s 后） | 8 | **200×4 + 429×4** | 3s 补 6 个但桶上限 4，仍只过 4 |
| burst C（再等 1s） | 4 | **200×2 + 429×2** | 1s 只补 2 个 → 过 2 拒 2 |

要点：

- 令牌状态存 Redis：`request_rate_limiter.{<key>}.tokens` / `.timestamp`，由 Lua 脚本原子更新（可用 `redis-cli monitor` 观察），key TTL 仅几秒、桶满即过期，属自清洁设计。
- 网关 429 是**空响应体**，与 Sentinel 的 `Blocked by Sentinel (flow limiting)` 文案可区分限流发生在哪一层。
- 两层限流可同时存在：网关按用户挡突发，Sentinel 按资源保服务，互不冲突。

**5）进阶实测（分桶隔离 + Redis 故障行为）**

- 分桶隔离：用户 A 打满自己桶（200×4+429×4）后，用户 B 立即请求仍 200×4——KeyResolver 按 token hash 分桶，互不影响；
- Redis 宕机：Lettuce 持续重连（~30s/次），期间请求挂起/超时；Redis 恢复后自动重连（日志 `Reconnected to 127.0.0.1:6379`），**限流自动恢复、无需重启网关**；
- 生产启示：Redis 是网关限流的单点，需哨兵/集群高可用，或自定义 `RedisRateLimiter` 加本地回退。

## 九、Seata AT 分布式事务

跨库跨服务下单：order-service 写 `order_db.t_order`，同时 Feign 调 user-service 扣 `study_cloud.t_user.balance`。任一步失败，两个库的数据必须一起回滚。

### 9.1 三个角色

| 角色 | 是谁 | 职责 |
|------|------|------|
| **TC**（事务协调器） | Seata Server :8091，独立进程 | 维护全局事务/分支事务状态，驱动二阶段提交或回滚，状态存 MySQL（seata 库 4 张表） |
| **TM**（事务管理器） | order-service（标 `@GlobalTransactional` 的方法） | 开启/提交/回滚全局事务，拿到全局 XID |
| **RM**（资源管理器） | 每个写库的服务（order-service + user-service） | 向 TC 注册分支、上报本地事务状态、执行回滚 |

TM 与 RM 之间没有层级调用关系，它们都只与 TC 通信（Netty TCP 长连接）。XID 随 Feign 请求头（`TX_XID`/`X-TX-XID`）自动透传，把多个服务绑进同一个全局事务。

### 9.2 服务端（TC）

- 版本 **2.0.0**，注册中心/配置用 `conf/application.yml`（config 用 file、registry 用 nacos、store 用 db）
- 独立 `seata` 库存 4 张表：`global_table`、`branch_table`、`lock_table`、`distributed_lock`（脚本 `script/server/db/mysql.sql`），**禁止混入业务库**
- **必须 JRE 8 启动**：jraft-core 1.3.13 与 JDK 21 不兼容；学习机把堆调到 `-Xmx256m`（默认 2G + 1G 直接内存会挤爆）
- 启动带 `-h 127.0.0.1`：多网卡机器防止绑定/注册到虚拟网卡 IP

### 9.3 客户端（业务服务）

1. 依赖：`spring-cloud-starter-alibaba-seata`
2. 每个业务库建 `undo_log` 表（`script/client/at/db/mysql.sql`）——回滚快照
3. `application.yml`：

```yaml
seata:
  tx-service-group: default_tx_group        # 事务分组
  service:
    vgroup-mapping:
      default_tx_group: default             # 分组 → TC 集群名
  registry:                                  # 从 Nacos 找 TC
    type: nacos
    nacos: { server-addr: 127.0.0.1:8848, group: SEATA_GROUP, application: seata-server, cluster: default }
  enable-auto-data-source-proxy: true        # 自动代理数据源，拦截 SQL 生成快照
```

4. 入口方法一个注解：`@GlobalTransactional(rollbackFor = Exception.class)`，本地分支照常写 `@Transactional`/直接 Mapper。

### 9.4 AT 两阶段机制

- **一阶段（业务执行时）**：拦截 SQL，查前镜像 → 执行业务 SQL 并**本地提交** → 查后镜像，连同全局锁信息写入 `undo_log`。锁的是"逻辑行"（主键），不是数据库行锁，不长期占连接。
- **二阶段提交**：各分支都成功 → TC 异步删除各库 `undo_log`（极快，无反向补偿）。
- **二阶段回滚**：任一分支失败 → RM 按 `undo_log` 前镜像生成反向 SQL 自动补偿（INSERT↔DELETE、UPDATE 反向更新），并校验后镜像防止脏写。

### 9.5 实测

| 用例 | 请求 | t_order | balance |
|------|------|---------|---------|
| 正常提交 | `POST /orders/seata/create?userId=1&amount=100` | 新增 1 条 | 1000 → 900 |
| 强制失败 | 同上加 `forceFail=true` | 插入后**回滚消失** | 扣款后**恢复** |

启动成功的判据：日志 `register TM success` + `register RM success`。注意 **AT 回滚不重置自增 ID**（被回滚的订单号会"跳号"），这是数据库机制，正常现象。

### 9.6 AT 的边界（重要）

undo_log 只代理受管数据源的 SQL。以下操作 AT **管不到**，需用 TCC/Saga 或事务消息兜底：

- 发 MQ、写 Redis、调第三方 HTTP
- 手动拿到原生连接执行的 SQL、跨非关系型存储

所以下一章的 RocketMQ 消息放在**全局事务方法返回之后**发送，避免"事务回滚了消息却发出去"。

## 十、RocketMQ 消息队列

解决两类问题：**异步解耦**（下单后加积分/通知，不等下游）与**最终一致**（本地事务与发消息的原子性）。

### 10.1 架构与端口

| 组件 | 端口 | 职责 |
|------|------|------|
| NameServer | 9876 | 无状态路由中心：Broker 注册，Producer/Consumer 查路由 |
| Broker | 10911（10909 VIP） | 存消息、管 Topic 队列、事务状态 |
| Producer | order-service | `rocketMQTemplate.syncSend(...)` / `sendMessageInTransaction(...)` |
| Consumer | user-service | `@RocketMQMessageListener` 两个监听器 |

本机版本：Broker **4.9.7** + rocketmq-spring-boot-starter **2.3.0**。

### 10.2 场景一：普通消息异步加积分（Seata + MQ 组合）

链路：`@GlobalTransactional` 下单（写订单 + 扣余额）**本地事务提交后** → afterCommit 回调中 `syncSend("order-topic:ORDER_CREATED", msg)` → user-service 的 `OrderPointsConsumer` 消费 → 先写积分流水占坑，再 `points += 金额`。

- Topic `order-topic`，Tag `ORDER_CREATED`，消费组 `user-service-points-consumer-group`
- `syncSend` 同步等 Broker ACK（只等 Broker 收下，**不等消费者执行**），失败按 `retry-times-when-send-failed` 重试
- **消息可靠性双保险**：发送侧 afterCommit + 消费侧幂等流水，详见 10.6
- 实测：amount=1 → balance 725→724，points 41→42（提交后约几十毫秒可见）

### 10.3 场景二：事务消息（半消息 + 回查）

"写订单"和"发消息"两步不能出现中间态：订单写成功但消息丢了 → 用户永远拿不到积分；消息先发出去但订单回滚 → 凭空发积分。RocketMQ 事务消息用**两阶段 + 回查**解决：

```
Producer                    Broker                     Consumer
   │  1.发半消息(half)         │                            │
   │ ────────────────────────▶│ 暂存，对消费者不可见         │
   │  2.执行本地事务            │                            │
   │    （insert t_order）      │                            │
   │  3.返回 COMMIT/ROLLBACK/UNKNOWN                         │
   │ ────────────────────────▶│                            │
   │        COMMIT             │ 4.消息变可见 ─────────────▶│ 消费加积分
   │        ROLLBACK           │ 直接丢弃，无人可见           │
   │        UNKNOWN            │ 5.定时回查 Producer          │
   │ ◀──── checkLocalTransaction ────                       │
   │ 查业务记录 → COMMIT/ROLLBACK                            │
```

代码对应：`OrderTxService.createOrderWithTxMessage` 发半消息，`OrderTransactionListener` 实现两个回调：
- `executeLocalTransaction`：执行 insert，成功返 `COMMIT`、异常返 `ROLLBACK`、不确定返 `UNKNOWN`
- `checkLocalTransaction`：Broker 回查时查业务状态（学习版固定 COMMIT，生产按业务流水号查表）

> 常量名坑：starter 2.3.0 是 `RocketMQLocalTransactionState.COMMIT / ROLLBACK / UNKNOWN`，**不是** 旧资料里的 `COMMIT_MESSAGE / ROLLBACK_MESSAGE`。

**回查实测定时证据**（`forceUnknown=true`，amount=5）：

| 时刻 | 事件 | points |
|------|------|--------|
| 18:34:28 | 半消息发出，本地 insert 成功但返回 UNKNOWN | 30（不变） |
| 8s 后查询 | 消息仍不可见，消费端毫无动静 | 30 |
| **18:35:18**（约 50s 后） | Broker 第 1 次回查（日志 `TRANSACTION_CHECK_TIMES=1`）→ COMMIT | 30 → **35** |

### 10.4 两种一致性方案怎么选

| | Seata AT（第九章） | RocketMQ 事务消息 |
|---|---|---|
| 一致性 | 强一致（二阶段自动补偿） | 最终一致（秒级延迟） |
| 适用 | 同构数据库、短事务、要求立即一致 | 跨异构系统、异步链路、可容忍延迟 |
| 代价 | undo_log、全局锁、TC 独立组件 | Broker 半消息存储与定时回查 |
| 典型 | 下单 + 扣余额 + 扣库存 | 下单 → 积分/通知/物流，与第三方系统对接 |

本项目把两者组合：**库内强一致交给 Seata，库外异步解耦交给 RocketMQ**。

### 10.5 Windows 本机部署要点

1. 启动两个独立窗口：先 NameServer（`start-namesrv-jre8.bat`）后 Broker（`start-broker-jre8.bat`）
2. 学习机必须调小 JVM：NameServer 256m、Broker 堆 512m + 直接内存 512m（默认 2G+15G）
3. `conf/broker.conf` 固定 `brokerIP1=127.0.0.1`，否则 Broker 注册到 VMware/WSL 虚拟网卡 IP
4. Topic 显式创建最稳妥（`autoCreateTopicEnable=true` 本机偶发不生效，消费者会订阅报 `topic not exist`）：

```bash
set ROCKETMQ_HOME=D:\rocketmq
mqadmin.cmd updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t order-topic
mqadmin.cmd updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t order-tx-topic
# 看消费进度/积压：mqadmin.cmd consumerProgress -n 127.0.0.1:9876 -g <消费组名>
```

### 10.6 消息可靠性：发送时机 + 消费幂等 + 死信

MQ 语义是**至少一次**（不丢，但可能重复）。项目用"发送侧 + 消费侧"两道防线把它收敛成业务视角的精确一次：

**① 发送侧：事务提交后才发（afterCommit）**

不能在 `@GlobalTransactional` 方法体里直接 `syncSend`——方法返回 ≠ 全局事务已提交，存在"消息发出、事务回滚 → 凭空加积分"的窗口。正确做法：

```java
@GlobalTransactional(rollbackFor = Exception.class)
@Transactional(rollbackFor = Exception.class)   // 必须有本地事务才能注册回调
public Long createOrder(...) {
    // ...写订单、扣款...
    OrderMessage msg = new OrderMessage(order.getId(), "ORDER-" + order.getId(), ...);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {           // 回滚时此回调不执行
            rocketMQTemplate.syncSend("order-topic:ORDER_CREATED", msg);
        }
    });
    return order.getId();
}
```

实测日志时序：分支1/2 → **事务提交后发送** → Seata RM 分支提交。`forceFail=true` 时：500、订单与扣款回滚、afterCommit 不触发、无消息无流水。

> 注意：`@GlobalTransactional` 本身不是 Spring 本地事务，单独存在时 `TransactionSynchronizationManager.isSynchronizationActive()` 为 false，注册不了回调，必须叠加 `@Transactional`。代价是 Feign 调用期间连接被占用，生产可用本地消息表替代。

**② 消费侧：唯一索引流水表幂等**

`t_points_log.biz_no` 唯一索引，消费时**先插流水（占坑）再加积分**，两步同一本地事务：

```
INSERT 流水成功 → 首次消息 → 加积分
DuplicateKeyException → 重复投递 → return（ACK 跳过），绝不重复加积分
```

- bizNo 生成：普通消息 `ORDER-{orderId}`；事务消息发半消息前预生成 UUID（此时订单未插入、orderId 为 null）
- 幂等键随消息体流动，Broker 重投/rebalance/消费者重启都能识别"同一条"
- 唯一索引优于"先 select 后 insert"：后者并发下有竞态，数据库约束才是最终兜底
- 插流水与加分同事务：加分失败一起回滚，重投时可重新处理（不会被误判为重复）

实测四组：首次消费加积分写流水 / 预埋流水模拟重复（积分不动、日志 `【幂等拦截】`）/ 后续正常消息不受影响 / 事务消息 UUID 流水。

> 与 Redis Token 幂等的区别：Token 防的是**入口处客户端重复提交**（先领票、一票一销），无法用于 MQ（Broker 重投没有领票环节）；两者在生产中分层共存。

**③ 兜底：重试 16 次 → 死信队列 `%DLQ%<消费组>`**

消费持续失败（毒消息）时消息进死信 Topic 隔离，不再自动投递，靠监控告警 + 人工/程序补偿；重试 Topic 为 `%RETRY%<消费组>`。消息从生产到终局：

```
afterCommit 发送（不回滚的消息才出门）
  → Broker 存储投递
  → 消费成功 ACK
  → 失败递增重试（10s…2h，共 16 次）
  → 仍失败 → %DLQ% 死信隔离，等待人工补偿
消费全程：bizNo 唯一索引保证重复投递只生效一次
```

## 十一、踩坑记录

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
- Windows PowerShell 5.1 的 `Invoke-WebRequest` 无 `-SkipHttpErrorCheck` 参数，并发压测改用 `System.Net.Http.HttpClient`；且需先设 `ServicePointManager.DefaultConnectionLimit=100`，否则每服务器仅 2 连接、并发不出去
- 慢调用策略必须并发触发：单请求本身要 300ms，顺序请求 1 秒窗口最多 3 个，达不到最小请求数 5
- 网关限流的 Redis key 里嵌的是 Java `String.hashCode()`（KeyResolver 里算的），在 PowerShell 里用 `.GetHashCode()` 算出的值不同（.NET 哈希算法不同），查 key 时要用 MONITOR 里看到的真实 key 名

**Seata**

- Seata Server 2.0.0 必须用 **JRE 8** 启动：jraft-core 1.3.13 在 JDK 21 下直接异常；MySQL 驱动要 8.x 并显式写 `driver-class-name: com.mysql.cj.jdbc.Driver`
- TC 端 4 张表必须在独立 `seata` 库；每个业务库（order_db、study_cloud）各建一张 `undo_log`，缺表回滚时才报错
- `store.db` 的表名拼写敏感：`global_table`、`distributed_lock` 等拼错（如 `glable_table`）不会启动失败，而是静默不可用
- 多网卡环境启动加 `-h 127.0.0.1`；但要分清"虚拟网卡 IP"和"真实 WLAN IP"——Seata 通常已自动跳过 VMware 网卡，注册的是当前物理网卡 IP，**本机连该 IP 本就可达**。排查连接问题先按「进程在不在 → 端口监听没 → TCP 连不连得通」顺序验证，不要一上来就怀疑注册 IP
- 内存吃紧时（本机 16G 跑全套组件）Seata 会启动后闪退且无明显报错，把 `-Xmx/-Xms` 降到 256m；`netstat` 短暂看到监听不等于进程活着
- 客户端报 `No available service`：九成是 TC 没起来或注册 IP 过期（换 WiFi/地点后 Nacos 缓存旧 IP），重启 TC 等 10~15s 心跳刷新即可
- AT 回滚不会回退自增主键（被回滚的订单 id 照样跳号）；MQ/Redis/HTTP 调用不在 AT 保护范围内

**RocketMQ**

- Windows 自写 `.bat`/`.cmd` 用**纯英文 ASCII**（或 GBK 编码保存），UTF-8 中文注释会让 cmd 解析乱码、双击闪退
- 默认 JVM 极大（Broker `-Xms2g -Xmx2g -XX:MaxDirectMemorySize=15g`），学习机改 `runserver.cmd`/`runbroker.cmd` 调小
- Broker 多网卡要配 `brokerIP1=127.0.0.1`；改完 Broker 配置必须**重启 Broker**
- Topic 不存在时生产者不报错、消费者订阅刷 `topic not exist`；`autoCreateTopicEnable=true` 不可靠，用 `mqadmin updateTopic` 显式建
- `mqadmin.cmd` 依赖 `ROCKETMQ_HOME` 环境变量，新窗口先 `set ROCKETMQ_HOME=D:\rocketmq`
- rocketmq-spring 2.3.0 事务状态常量是 `COMMIT/ROLLBACK/UNKNOWN`，旧教程的 `COMMIT_MESSAGE/ROLLBACK_MESSAGE` 编译不过
- 生产者代码在 order-service、消费者在 user-service，改完**两边都要重新打包重启**；只重启一边是最常见的"消息没反应"原因
- 排查消息链路三板斧：`mqadmin topicList` 看 Topic、`consumerProgress -g <组>` 看消费偏移/积压、`%USERPROFILE%\logs\rocketmqlogs\rocketmq_client.log` 看客户端注册与订阅报错
- 事务消息 UNKNOWN 后不要傻等：Broker 默认约 60s 才首次回查（`transactionCheckInterval`），回查日志在生产者端（order-service），不在 Broker 控制台
- MQ 消费必须做幂等：唯一索引 INSERT 冲突即重复消息，正常 return 即 ACK；注意冲突失败的 INSERT 也会烧掉一个自增 ID（流水表 id 出现"空洞"是正常现象）
- afterCommit 回调前提是方法上有活跃的 Spring 事务（`@Transactional`）；只有 `@GlobalTransactional` 时注册回调不生效
- 消费端"用户不存在"这类坏消息要抛异常让其重试，不能 return 当成功 ACK，否则消息被当成已消费永久丢失

## 十二、Docker Compose 容器化编排

从"9 个黑窗口手动启动"进化到 `docker compose up -d` 一键拉起全套环境。

### 12.1 服务清单

10 个服务全部跑在 `study-net` 自定义桥接网络内，通过**服务名**互相访问。

| 服务 | 镜像 | 端口 | 数据卷 | 说明 |
|------|------|------|--------|------|
| mysql | mysql:8.0 | 3306 | mysql-data | 3 个业务库：study_cloud / order_db / seata |
| redis | redis:7.4 | 6379 | redis-data | 缓存 + 网关限流 |
| nacos | nacos/nacos-server:v2.4.0 | 8848, 9848 | nacos-data | 注册中心 + 配置中心（单机） |
| namesrv | apache/rocketmq:4.9.7 | 9876 | - | RocketMQ 路由中心 |
| broker | apache/rocketmq:4.9.7 | 10911, 10909 | rocketmq-store, rocketmq-logs | 消息存储中转 |
| seata | seataio/seata-server:2.0.0 | 8091, 7091 | -（挂载 application.yml） | 分布式事务 TC |
| zipkin | openzipkin/zipkin:3 | 9411 | - | 链路追踪（可选） |
| user-service | user-service:1.0.0 | 8081 | - | 用户服务 |
| order-service | order-service:1.0.0 | 8082 | - | 订单服务 |
| gateway-service | gateway-service:1.0.0 | 9000 | - | 网关 |

### 12.2 一键启动

```powershell
# 构建微服务镜像（首次或代码改动后）
docker build -t user-service:1.0.0 ./user-service
docker build -t order-service:1.0.0 ./order-service
docker build -t gateway-service:1.0.0 ./gateway-service

# 启动全部
docker compose up -d

# 查看状态
docker compose ps

# 看日志
docker compose logs -f order-service

# 停止
docker compose down          # 只删容器，保留数据卷
docker compose down -v       # 连数据卷一起删（慎用！数据全清）
```

### 12.3 Dockerfile 设计（微服务通用模板）

```dockerfile
FROM eclipse-temurin:17-jre      # 只用 JRE，不用 JDK，镜像更小
WORKDIR /app
COPY target/user-service-1.0.0.jar app.jar
EXPOSE 8081
ENV TZ=Asia/Shanghai
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
```

### 12.4 配置外部化：一份配置跑两种环境

`application.yml` 里所有 `127.0.0.1` 改成 `${ENV:default}` 占位符：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:127.0.0.1}:3306/study_cloud?...
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
rocketmq:
  name-server: ${NAMESRV_ADDR:127.0.0.1:9876}
management:
  zipkin:
    tracing:
      endpoint: http://${ZIPKIN_HOST:127.0.0.1}:9411/api/v2/spans
```

- **IDEA 本地跑**：不传环境变量 → 用默认值 `127.0.0.1`
- **容器跑**：compose 里传服务名 → `NACOS_ADDR=nacos:8848`

### 12.5 数据初始化（首次启动必做）

Compose 创建的是**全新数据卷**（带项目名前缀 `spring-cloud-study_`），MySQL/Nacos 都是空的，需要导入备份。

**MySQL 三个库**：

```powershell
Get-Content docker/backup/order_db.sql     | docker exec -i mysql mysql -uroot -p123456
Get-Content docker/backup/study_cloud.sql  | docker exec -i mysql mysql -uroot -p123456
Get-Content docker/backup/seata.sql        | docker exec -i mysql mysql -uroot -p123456
```

**Nacos 配置**（通过 OpenAPI 导入）：

```powershell
# user-service 配置
curl.exe -s -X POST "http://localhost:8848/nacos/v1/cs/configs" `
  --data-urlencode "dataId=user-service.yaml" `
  --data-urlencode "group=DEFAULT_GROUP" `
  --data-urlencode "content@docker/backup/nacos-configs/user-service.yaml"

# Sentinel 流控/降级规则（可选，文件为空则不导入）
```

### 12.6 启动顺序与健康检查

`depends_on` **只保证容器启动顺序，不保证服务就绪**。MySQL 容器进程起来了，但能接受连接可能还要 10-20 秒。

学习环境用 `restart: unless-stopped` 兜底——微服务连不上依赖就崩溃，自动重启重试，等基础设施就绪后自然连上。

生产环境推荐加 `healthcheck` + `depends_on: condition: service_healthy`。

### 12.7 踩坑记录

**坑 1：端口被宿主机旧进程占用**

`docker compose up` 报 `ports are not available: 9411`。之前 Windows 上跑的 Zipkin 还占着 9411，容器映射同端口失败。

```powershell
Get-NetTCPConnection -LocalPort 9411 | Select-Object OwningProcess
Stop-Process -Id <PID> -Force
```

**坑 2：broker 无限重启（exit 253），日志为空**

最棘手的坑。排查了三层才找到根因：

1. 先怀疑启动顺序 → 手动跑（不挂卷）成功，排除
2. 再对比发现：**挂 named volume 就崩，不挂就正常**
3. 根因：Docker named volume 默认归 root，RocketMQ 镜像用 `rocketmq` 用户运行，**无写权限**，JVM 在日志初始化前就崩溃了，所以 `docker logs` 没输出

解决（学习环境简化）：

```yaml
broker:
  user: root    # 加这一行
```

> 另：旧卷被停止的容器引用着删不掉，需先 `docker rm -f broker` 再 `docker volume rm`。

**坑 3：MySQL 数据全没了**

`docker run -v mysql-data:...` 创建的卷叫 `mysql-data`；compose 自动加项目前缀，创建的是 `spring-cloud-study_mysql-data`。**两个完全不同的卷**，新 MySQL 里只有 4 个系统库。

解决见 12.5 导入备份。复用旧卷的方式：

```yaml
volumes:
  mysql-data:
    external: true    # 复用已存在的 mysql-data 卷
```

**坑 4：Nacos 配置也是空的**

同理，新 nacos-data 卷是空的，需导入 `user-service.yaml` 等配置。

**坑 5：`depends_on` 不等于"就绪"**

微服务首次启动时 MySQL/Nacos 可能还没准备好，导致崩溃。靠 `restart: unless-stopped` 自动重试解决。

### 12.8 全链路验证

```powershell
# 1. 登录（网关 9000 转发到 user-service）
curl -X POST http://localhost:9000/api/login -H "Content-Type: application/json" `
  -d '{"username":"zhangsan","password":"123456"}'

# 2. 正常下单（Seata AT 提交：扣余额 + 建订单）
curl -X POST "http://localhost:9000/api/orders/seata/create?userId=1&amount=100" `
  -H "Authorization: Bearer <token>"

# 3. forceFail 下单（Seata AT 回滚：余额不扣、订单不建）
curl -X POST "http://localhost:9000/api/orders/seata/create?userId=1&amount=100&forceFail=true" `
  -H "Authorization: Bearer <token>"

# 4. RocketMQ 事务消息（积分异步 +100）
# 5. Redis 缓存（GET /api/orders/1 两次，第二次命中 orderCache::users::1）
```

验证通过标准：登录返回 token、正常下单余额扣减、forceFail 后余额不变、积分增加、Redis 有缓存 key。
