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
| Sentinel Dashboard | 1.8.8（容器化控制台，:8858，账号/密码均 sentinel） |
| Micrometer Tracing + Zipkin | 全链路追踪 |
| Micrometer + Prometheus | v2.54.1（指标采集，:9090） |
| Grafana | 11.2.0（可视化面板，:3000，admin/admin123） |
| JMeter | 5.6.3（CLI 压测，四轮场景实测） |
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
可观测性：Prometheus :9090（抓 3 个微服务 /actuator/prometheus）
          Grafana :3000（Prometheus 数据源自动注入，QPS/P95/429/熔断面板）
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
| `sql/dlq_demo.sql` | — | order_db.t_dead_letter 死信记录表（可见 + 可补偿） |

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
#   (6) sql/dlq_demo.sql          order_db.t_dead_letter 死信记录表
mysql -u root -p < sql/init.sql

# Redis：默认 6379 端口

# Sentinel Dashboard：独立控制台（可选，账号/密码均为 sentinel）
java "-Dserver.port=8858" -jar sentinel-dashboard-1.8.8.jar

# 可选：Zipkin 链路追踪
java -jar zipkin-server.jar

# 可选：Prometheus + Grafana（可观测性，详见十五章）
#   本机手动方式较繁琐，推荐直接用 Docker Compose（见 12.2 / 十五章），
#   compose 已含 prometheus/grafana 容器 + 自动注入数据源，up 即开箱可用

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
| 8 | `POST /api/orders/seata/create` | 直连 8082 测试 | Seata AT 下单：写订单 + 扣余额 + 发积分消息；`forceFail=true` 演示全局回滚；提交成功后自动发一条 10s 延迟消息做超时取消检查（见 19.2） |
| 9 | `POST /api/orders/rocketmq/create` | 直连 8082 测试 | RocketMQ 事务消息下单；`forceUnknown=true` 演示 Broker 事务回查 |
| 10 | `POST /api/orders/status-demo` | 需要 Token | 顺序消息演示：按 `orderId` 保序连发 6 条状态消息；`failStep` 模拟消费失败挂起队列（见 19.1） |
| 11 | `POST /api/orders/backlog-demo` | 需要 Token | 消息积压演示：`sendOneWay` 瞬时灌入 `count` 条慢消费消息（见 19.3）；`sleepMs=-1` 即"毒消息"，用于演示死信（见 20） |
| 12 | `GET /api/orders/dlq/list` | 需要 Token | 死信查询：`status`（0 待补偿 / 1 补偿成功）可选，返回死信记录含原始报文（见 20） |
| 13 | `POST /api/orders/dlq/retry` | 需要 Token | 死信补偿重投：读待补偿死信 → 修复 → 重投原 Topic，消费成功后回写 `status=1`（见 20） |

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
13. **顺序消息**：`/orders/status-demo?orderId=301` 连发 6 条状态消息，order-service 日志中同订单 step 严格 1→6 递增（详见 19.1）
14. **延迟消息**：下单后 10s 未支付自动取消（CAS 条件更新）；先支付则取消落空（详见 19.2）
15. **消息积压**：`/orders/backlog-demo` 灌入后 `mqadmin consumerProgress` 看 Diff Total 冲高回落（详见 19.3）
16. **死信队列闭环**：`/orders/backlog-demo?count=3&sleepMs=-1` 灌毒消息，约 45s 后自动进 `%DLQ%order-backlog-consumer-group` 并落库 `t_dead_letter`；调 `/orders/dlq/retry` 修复重投后 `status` 回写为 1（一键脚本 `dlq-test.ps1`，详见 20）

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

13 个服务全部跑在 `study-net` 自定义桥接网络内，通过**服务名**互相访问。

| 服务 | 镜像 | 端口 | 数据卷 | 说明 |
|------|------|------|--------|------|
| mysql | mysql:8.0 | 3306 | mysql-data | 3 个业务库：study_cloud / order_db / seata |
| redis | redis:7.4 | 6379 | redis-data | 缓存 + 网关限流 |
| nacos | nacos/nacos-server:v2.4.0 | 8848, 9848 | nacos-data | 注册中心 + 配置中心（单机） |
| namesrv | apache/rocketmq:4.9.7 | 9876 | - | RocketMQ 路由中心 |
| broker | apache/rocketmq:4.9.7 | 10911, 10909 | rocketmq-store, rocketmq-logs | 消息存储中转 |
| seata | seataio/seata-server:2.0.0 | 8091, 7091 | -（挂载 application.yml） | 分布式事务 TC |
| zipkin | openzipkin/zipkin:3 | 9411 | - | 链路追踪 |
| prometheus | prom/prometheus:v2.54.1 | 9090 | -（挂载 prometheus.yml） | 指标采集（15s 抓取 3 微服务） |
| grafana | grafana/grafana:11.2.0 | 3000 | grafana-data（挂载 provisioning） | 可视化面板（admin/admin123，数据源自动注入） |
| sentinel-dashboard | bladex/sentinel-dashboard:1.8.8 | 8858 | - | Sentinel 控制台（sentinel/sentinel） |
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

### 12.8 全链路验证（一键脚本）

统一返回体改造后，推荐直接跑自动化脚本，覆盖 58 项断言（登录 / 参数校验 / 网关鉴权 / Seata 提交与回滚 / RocketMQ 普通消息+事务消息 / Redis 缓存 / 接口幂等 / 链路追踪 / Sentinel 流控与熔断），全部基于数据库前后差值校验；脚本开头会把测试账户重置为余额、积分各 1000，可无限次重复执行：

```powershell
.\scripts\verify-full-chain.ps1   # 全绿退出码 0，有失败非 0（可接入 CI）
.\scripts\cas-race-test.ps1       # 延迟消息支付/取消并发竞态测试（CAS 条件更新验证）
.\scripts\msg-backlog.ps1         # 消息积压验证：sendOneWay 瞬时洪峰 vs 慢消费，观察 Diff Total 冲高→归零
.\scripts\dlq-test.ps1            # 死信队列验证：毒消息 → 重试耗尽 → 死信落库 → 补偿重投 → 回写 status=1（9 项断言）
```

消息积压监控辅助脚本：

```powershell
.\scripts\lag-pusher.ps1          # 每 15s 从 broker mqadmin 拉 Diff Total → 推送到 lag-report → Micrometer Gauge（见第二十一章）
```

脚本统一放在 `scripts/` 目录；只依赖 HTTP 与 docker exec，从任意目录运行均可。三大特性（顺序 / 延迟 / 积压）的原理与实测数据见第十九章，死信闭环见第二十章。

也可在 Postman 导入 `docker/backup/Spring-Cloud-FullChain.postman_collection.json`，用 Collection Runner 顺序执行 8 个文件夹共 20 个请求（登录 Tests 自动提取 `data.token` 存入集合变量，幂等用例自动传递 `idemToken`，无需手动复制）。

手动验证的关键请求（经网关 9000）：

```powershell
# 1. 登录（账号是中文"张三"；成功返回 {code:200,data:{token,...}}）
curl.exe -X POST http://localhost:9000/api/login -H "Content-Type: application/json" `
  --data-raw '{"username":"张三","password":"123456"}'

# 2. 正常下单（Seata AT 提交：扣余额 + 建订单 + afterCommit 普通消息加积分）
curl.exe -X POST "http://localhost:9000/api/orders/seata/create?userId=1&amount=100" `
  -H "Authorization: Bearer <token>"

# 3. forceFail 下单（Seata AT 回滚：HTTP 500，余额/订单/积分均不变）
curl.exe -X POST "http://localhost:9000/api/orders/seata/create?userId=1&amount=100&forceFail=true" `
  -H "Authorization: Bearer <token>"

# 4. RocketMQ 事务消息（约 10~20 秒 Broker 回查确认后积分 +50）
curl.exe -X POST "http://localhost:9000/api/orders/rocketmq/create?userId=1&amount=50" `
  -H "Authorization: Bearer <token>"

# 5. Redis 缓存（GET /api/orders/1 两次，Redis 出现 key: orderCache::users::1）
```

### 12.9 工程规范：common 模块 + 统一返回体

容器化之后抽了一个共享模块 `common`，把两个业务服务重复的返回格式与异常处理收敛到一处：

```
common/src/main/java/com/study/cloud/common/
├── result/Result.java            统一返回体 {code, message, data}
│                                 Result.success(data) / Result.fail(code, msg)
├── result/ResultCode.java        状态码枚举（200/400/401/500）
├── exception/BusinessException.java  业务异常（携带 code，构造方法用 this(...) 链式复用）
└── web/GlobalExceptionHandler.java   @RestControllerAdvice 全局异常处理
```

核心约定：

1. **所有接口返回 `Result<T>`**，前端只解析一种 JSON 结构；`T` 是泛型，登录装 `Map`、查询装实体、无数据装 `Void`。
2. **HTTP 状态码与业务 code 同步设置**：业务失败必须由全局处理器用 `ResponseEntity.status(4xx/5xx)` 返回真实 HTTP 状态码，而不是直接 `return Result.fail(...)`（那会是 HTTP 200）。这一点是 Seata 回滚的前提——Feign 靠 HTTP 4xx/5xx 判定远程失败并抛异常，全局事务才会回滚。
3. **异常归类**：
   - `BusinessException` → 按其 code 返回（401 登录失败、400 余额不足、500 forceFail）
   - `MethodArgumentNotValidException`（`@Valid` body 校验）→ 400
   - `ConstraintViolationException`（`@Validated` 参数校验）→ 400
   - `MissingServletRequestParameterException`（必填参数缺失）→ 400
   - `MethodArgumentTypeMismatchException`（参数类型不匹配）→ 400
   - 其余 `Exception` → 500 兜底，消息统一"系统繁忙，请稍后再试"，堆栈只打日志不外泄
4. Feign 接口同步返回 `Result<UserDto>`，调用方先判 `code` 再取 `data`；降级工厂保持不变。

### 12.10 内存优化（5.1GB → 2.9GB）

10 个容器全起后 WSL2 占用约 5GB。学习环境数据量极小，按"JVM 堆 + cgroup limit 两层收敛、中间件用各自的内存旋钮"原则调优，实测降到约 2.9GB 且无 OOM：

| 容器 | 优化前 | 优化后 | 手段 |
|---|---|---|---|
| mysql | 574 MB | ~140 MB | `--performance-schema=OFF --mysqlx=0`，buffer-pool 128M |
| order-service | 975 MB | ~430 MB | `-Xms128m -Xmx384m -XX:MaxMetaspaceSize=256m` |
| user/gateway | 533/497 MB | ~370/360 MB | `-Xms128m -Xmx256m -XX:MaxMetaspaceSize=256m` |
| broker | 694 MB | ~440 MB | `JAVA_OPT_EXT` 堆 512→256m |
| nacos | 662 MB | ~500 MB | `JVM_XMS/XMX` 128/256m |
| seata | 597 MB | ~390 MB | `JVM_XMS/XMX` 128/256m，直接内存 128m |
| namesrv / zipkin | 158/287 MB | ~125/200 MB | 堆压到 128m / 128-256m |

每个服务另加 `mem_limit` 作为 cgroup 硬上限（JVM 堆 < 容器 limit，防 OOMKilled）。

**WSL2 全局**：新建 `C:\Users\<用户>\.wslconfig`：

```ini
[wsl2]
memory=5GB
swap=2GB
autoMemoryReclaim=gradual
```

要点：

- `-Xms` 是初始堆，写大了 JVM 启动即全额认领；学习环境给小初始堆 + 合理上限即可。
- JVM 实际常驻 ≈ 堆 + Metaspace + 线程栈 + 直接内存，通常是堆的 1.5~2 倍，所以 `mem_limit` 不能只按 `-Xmx` 设。
- WSL2 借走的内存默认不主动归还 Windows（只涨不跌是正常现象，非泄漏）；改 `.wslconfig` 后需 `wsl --shutdown` 完全重启才生效。
- ⚠️ Windows PowerShell 5.1 执行带中文的脚本/请求有两个编码坑：① `.ps1` 含中文须保存为 **带 BOM 的 UTF-8**，否则按 GBK 解析导致语法错误；② `Invoke-RestMethod` 发中文 body 要显式 `[Text.Encoding]::UTF8.GetBytes(...)`。脚本中均已处理。

## 十三、生产级三要素：接口幂等 / 服务高可用 / 链路追踪

前面把功能跑通了，这一课补齐"上生产"前绕不开的三件事：**重复请求只生效一次、单实例挂了业务不停、出问题能按一次请求把日志和调用链串起来**。

### 13.1 接口幂等：Redis 防重令牌 + Lua 原子消费

**问题**：下单这类写接口，重复执行会直接造成资损。重复来自三条路：用户狂点提交按钮、网络抖动导致客户端/网关超时重试、MQ 重投。本项目里 MQ 消费端已用 `t_points_log.biz_no` 唯一索引兜底（见 10.6），同步下单接口则采用"防重令牌"。

**流程**（代码：`IdempotentTokenService` + `OrderController` 两个新端点）：

```
进入下单页                提交订单（请求头 Idempotent-Token）
   │                            │
   ▼                            ▼
GET /orders/idempotent/token   ① Lua 原子"比对 owner + 删除"
Redis SET idem:seata:{uuid}    ├─ 返回 1（令牌有效）→ ② 执行业务下单
      = 用户标识 EX 300        └─ 返回 0（用过/不存在/盗用）→ 400 拒绝，业务零执行
```

两个关键设计：

1. **校验+销毁必须原子，所以用 Lua**。`GET` 判断存在再 `DEL` 是两条命令，高并发下两个请求可能都 `GET` 成功然后双双放行；Redis 单线程执行 Lua，比对与删除在一个脚本内完成，中间插不进别的命令：

   ```lua
   if redis.call('get', KEYS[1]) == ARGV[1] then
       return redis.call('del', KEYS[1])   -- 是我的令牌：销毁并放行
   else
       return 0                            -- 重复/不存在/别人的：拒绝
   end
   ```

2. **令牌 value 存签发用户**（网关注入的 `X-User-Id`）。即使 A 的令牌 UUID 被 B 拿到，owner 对不上一样消费失败；令牌 5 分钟 TTL，下单页放久自动作废。

实测（脚本第 8 章 9 项断言）：取令牌 → Redis 查到 key 且 TTL=300 → 首次提交 200、余额 -66、订单 +1、令牌被删除 → **同一令牌第二次 400「请勿重复提交」→ 不带令牌头 400** → 两次拦截后余额、订单数纹丝不动。

边界与选型说明：

- 令牌在**业务之前**消费：业务若因别的原因失败（如余额不足），令牌同样作废，用户需重新取令牌。它防的是重复提交，不保证业务成功。
- 防重令牌适合"客户端主动发起 + 交互前能取号"的场景；服务间回调/MQ 消费更适合**唯一键去重**（本项目积分流水即此方案）；强一致的资金核心则需要状态机 + 乐观锁，令牌只是第一道闸。

### 13.2 服务高可用：多副本 + Nacos 负载均衡 + 故障转移

**Compose 改造**：`user-service`/`order-service` 去掉 `container_name` 和宿主机 `ports`——前者副本同名会冲突，后者多副本抢同一宿主端口。外部本就统一走网关 9000，服务间走 Docker 网络用服务名互访，不需要宿主机直达：

```bash
# user/order 各起两个副本（容器名变为 project-user-service-1/2）
docker compose up -d --scale user-service=2 --scale order-service=2
```

**负载均衡**：两个副本启动后各自注册 Nacos（服务列表 2+2 healthy）。网关与 OpenFeign 内置 Spring Cloud LoadBalancer，默认**轮询**。`GET /api/orders/{id}` 响应里新增 `serverInstance` 字段（取容器主机名 = 容器 ID 前缀），连打 8 次严格 4:4 落到两个实例，肉眼可证。

**故障转移实测**：

| 操作 | 现象 | 原理 |
|---|---|---|
| `docker stop` 一个 order 副本 | 立刻连打 10 次请求，**0 失败**，流量全给存活实例 | 优雅停机：进程收到 SIGTERM 后主动从 Nacos 注销，新请求不再分过来 |
| 模拟宕机（`kill -9`/断电） | 约 **15 秒**内容错/失败窗口，之后摘除 | 进程没机会注销，靠 Nacos 心跳超时（默认 5s 心跳 × 数次）被动判定不健康 |
| `docker start` 恢复 | 重新注册，流量回到两实例轮询 | — |

要点：生产发布用滚动停机（先发 SIGTERM、等注销和在途请求处理完再退出，Spring Boot 优雅停机默认开启），用户无感知；而宕机场景靠客户端重试/降级兜住摘除前的空窗（Ribbon/LoadBalancer 也会对连接失败做重试，本项目 Sentinel 降级是同一位置的另一道防线）。

**内存代价**：副本数翻倍，user/order 各多出一份 JVM，实测 WSL2 总占用约 +0.8GB（2.9GB → 约 3.7GB，受 `.wslconfig` 5GB 上限约束仍可跑）。生产环境副本数 = 容量规划问题，不是越多越好。

### 13.3 链路追踪：X-Trace-Id 响应头 + 日志串联 + Zipkin

依赖（micrometer-tracing-bridge-brave + zipkin-reporter-brave）和 Zipkin 容器在容器化课程已就位，三个服务采样率均为 100%。但 traceId 默认只在服务内部可见，排查时调用方报不出"我这次请求的编号"。本课在 common 模块新增 `TraceIdResponseFilter`：

- `OncePerRequestFilter` + `@Order(0)`（排在 tracing 过滤器之后），从 SLF4J MDC 取 micrometer 写入的 `traceId`，回填响应头 **`X-Trace-Id`**；
- `finally` 兜底：链内才确定 traceId、或 4xx/5xx 异常响应时，只要响应未提交也补上——排查失败请求反而更需要它；
- 网关是响应式代理，自动透传下游响应头，无需改动。

**一条 traceId 的三种用法**（以 `GET /api/orders/2` 为例，实测 traceId=`6ac5c755...`）：

```powershell
# ① 调用方直接拿到编号（响应头）
$r = Invoke-WebRequest "http://localhost:9000/api/orders/2" -Headers @{Authorization="Bearer <token>"}
$r.Headers['X-Trace-Id']

# ② 多容器日志按同一 traceId 串联（compose 聚合两个副本的日志）
docker compose logs order-service --since 5m | Select-String '6ac5c755...'
docker compose logs user-service  --since 5m | Select-String '6ac5c755...'

# ③ Zipkin API / UI 看完整 span 链
curl.exe "http://localhost:9411/api/v2/trace/6ac5c755..."
# 浏览器：http://localhost:9411/trace/6ac5c755...
```

实测一条 `gateway → order → Feign → user` 的请求在 Zipkin 中是 **4 个 span**（网关入口/转发、`GET /orders/{id}`、`GET /users/{id}`），共享同一 traceId；日志中则表现为 `[traceId-spanId]`——**traceId 全链路相同，spanId 每跳不同**，靠 spanId 还能区分调用层次。

两个观察到的坑（值得记住）：

- **MyBatis 的 SQL 日志走 stdout 不经 SLF4J**，不带 `[traceId]`；想让 SQL 也进链路，要把 `log-impl` 换成走 SLF4J 的实现。
- **WebFlux 网关默认不把 MDC 写进日志**（响应式线程切换会丢 ThreadLocal），网关侧排查以 Zipkin span 为准；Servlet 栈的 user/order 日志则完整带 traceId。
- Redis 缓存命中时不会产生 user 服务 span（Feign 被短路），属正常现象。

### 13.4 顺带修复：坏 JSON 不再是 500

排查中用 curl 传了一个缺引号的非法 JSON，服务端返回 500。`HttpMessageNotReadableException`（请求体为空/JSON 非法）本质是客户端错误，已在 `GlobalExceptionHandler` 中归类为 **400「请求体格式错误或缺失」**，与参数缺失、类型不匹配一致。至此脚本第 1 章新增对应断言。

### 13.5 验证产物

| 产物 | 内容 |
|---|---|
| `verify-full-chain.ps1` | 58 项断言，新增第 8 章幂等（9 项，含 Redis TTL/令牌删除/重复与缺失拦截/DB 差值）、第 9 章追踪（X-Trace-Id 头、serverInstance、Zipkin span 覆盖 gateway+order）、第 10~12 章网关容错（超时/重试/熔断，共 6 项） |
| Postman 集合 | 8 个文件夹 20 个请求；新增「7. 接口幂等」「8. 分布式链路追踪」，原 Seata/RocketMQ 请求全部从直连 8082 改为经网关 9000 |
| 新增/改动代码 | `IdempotentTokenService`、`OrderController` 幂等两端点 + `serverInstance`、common 的 `TraceIdResponseFilter` 与坏 JSON 异常归类、compose 去固定名/端口支持 scale |

## 十四、网关容错三阶段：超时 → 重试 → 熔断

前面阶段网关只做路由转发和鉴权，下游服务出问题时没有任何防护。这一课按"由浅入深"给网关补齐三层容错机制：**超时（基础兜底）→ 重试（瞬时故障）→ 熔断（持续故障）**，同时修复了三个此前遗留的坑。

### 14.1 阶段1：网关超时控制

**问题**：`order-service` 没有配置任何超时，下游接口若长时间不响应（如 `/orders/timeout` sleep 5s），网关会一直挂起，浪费连接资源。

**改动**：`application.yml` 全局加 `httpclient.connect-timeout=2000` + `response-timeout=5s`，order 路由 metadata 单独设 `response-timeout=2000`（2s）。

**结果**：`/orders/timeout` 返回 **504 GATEWAY_TIMEOUT**，耗时 ~2s；其余正常接口不受影响。

### 14.2 阶段2：网关自动重试

**问题**：下游瞬时故障（网络抖动、瞬时 503）时，调用方直接拿到错误，体验差。

**改动**：order 路由加 `Retry` filter，参数：`retries=2`（首次+2=共3次）、`series=5xx`、`methods=GET`、`exceptions=[java.io.IOException]`、`backoff=指数退避(100ms→200ms)`。

**安全边界**：
- `methods=GET`：只重试幂等的 GET，POST 下单绝不能重试（会重复下单/与幂等令牌冲突）
- `series=5xx`：下游没处理成功才重试，4xx 参数错误重试无意义
- `exceptions` 只留 `IOException`：连接被拒（连接都没建立）才重试；**刻意不含 `TimeoutException`**——响应超时下游可能已在处理，重试会叠加成 6s

**结果**：`GET /api/orders/retry-demo`（前2次503第3次成功）实测 3 次尝试后返回 200。

### 14.3 阶段3：网关层熔断（CircuitBreaker）

**问题**：下游持续故障（失败率≥50%）时，重试毫无意义，反而会加剧下游压力，甚至拖垮网关自身。

**改动**：

1. `pom.xml` 引入 `spring-cloud-starter-circuitbreaker-reactor-resilience4j`
2. `application.yml` order 路由加 `CircuitBreaker` filter（在 `Retry` 之前 = 包裹 Retry，熔断打开时不执行重试也不打后端），配置 `fallbackUri=forward:/fallback/order` + `statusCodes=[500,503]`
3. Resilience4j 熔断器参数：`slidingWindowSize=10`、`failureRateThreshold=50`、`waitDurationInOpenState=10s`、`permittedNumberOfCallsInHalfOpenState=3`
4. **新增 `FallbackController`**：网关本地降级端点，从 `CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR` 取异常，4 类分类处理：
   - `CallNotPermittedException` → 503 CIRCUIT_OPEN
   - 超时类 → 504 RESPONSE_TIMEOUT
   - `HttpStatusCodeException`（业务真实500/503）→ **透传原始状态码**，`fallback=false`
   - 其它 → 503 DOWNSTREAM_ERROR

**结果**：`GET /api/orders/circuit-test`（前4次200之后持续503）实测闭环：
```
前4次 200 → 第5-10次 503（计入熔断统计）→ 第11次起 503 fallback=true
→ 10s 后半开探测 3 次 200 → 熔断关闭，恢复正常
```

### 14.4 顺手修复的三个坑

| 坑 | 根因 | 修复 |
|---|---|---|
| `/orders/timeout` 返回 503/1s 而非 504/2s | Resilience4j `TimeLimiter` 默认 1s 抢在网关 `response-timeout=2s` 前触发 | 配置 `timelimiter.configs.default.timeout-duration=10s` |
| Seata 回滚 forceFail 返回非 500 | `fallbackUri` 的 forward 保留原始 HTTP 方法，POST 到 `@GetMapping` 的 fallback 报 405 | 把 fallback 端点改为 `@RequestMapping`（不限制方法），并识别 `HttpStatusCodeException` **透传原始状态码** |
| PowerShell 5.1 读 5xx body 为空 | `$_.Exception.Response.GetResponseStream()` 对已缓冲响应返回空流 | 改用 `$_.ErrorDetails.Message` |

### 14.5 验证产物

`verify-full-chain.ps1` 新增第 10~12 章共 6 条断言，全链路从 34 项 → **46 项**：
- 第10章：超时 → 504 / 2s 内返回
- 第11章：重试 → 3 次尝试后 200
- 第12章：熔断闭环（CLOSED→OPEN→HALF_OPEN→CLOSED）

---

## 十五、Prometheus + Grafana 可观测性

前面各章的限流/熔断/重试效果，之前只能靠肉眼数响应码、翻日志判断。本章接入**指标采集 + 可视化**，把 QPS、延迟分布、429 拒绝率、熔断器状态实时画成图，压测时一目了然。

可观测性三大支柱：metrics（指标，本章）+ tracing（链路，见 13.3 Zipkin）+ logging（日志，未接入）。

### 15.1 整体链路

```
微服务 (Micrometer)                    Prometheus                 Grafana
/actuator/prometheus  ◄── 15s 抓取 ──  :9090 时序存储  ◄── PromQL 查询 ──  :3000 面板
   文本格式指标                            TSDB                        Dashboard
```

- 三个微服务各加一个依赖 `micrometer-registry-prometheus`，把 JVM/HTTP 指标以 Prometheus 文本格式暴露在 `/actuator/prometheus`。
- Prometheus 容器按 `docker/prometheus/prometheus.yml` 每 15s 抓取一次，存进自己的 TSDB。
- Grafana 容器通过 provisioning 自动挂载 Prometheus 数据源（`docker/grafana/provisioning/datasources/datasource.yml`），启动即可查，无需手动添加。

### 15.2 关键配置

**1）依赖（三个微服务 pom 相同）**：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**2）application.yml（三个微服务相同）**：给指标打 `application` 标签便于按服务筛选；开启 HTTP 请求直方图才能算 P95 分位数。

```yaml
management:
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true   # 关键：开启后才有 http_server_requests_seconds_bucket，才能 histogram_quantile 算 P95
  endpoints:
    web:
      exposure:
        include: '*'                  # 暴露 /actuator/prometheus
```

**3）Prometheus 抓取（`docker/prometheus/prometheus.yml`）**：多副本服务用 `dns_sd_configs` 的 A 记录解析自动发现所有副本 IP，不用写死容器名。

```yaml
scrape_configs:
  - job_name: gateway-service          # 单实例有 container_name，静态地址即可
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["gateway-service:9000"]
  - job_name: user-service             # --scale 多副本，dns_sd 返回所有副本
    metrics_path: /actuator/prometheus
    dns_sd_configs:
      - names: ["user-service"]
        type: A
        port: 8081
```

**4）compose 服务**：`grafana` 挂 `./docker/grafana/provisioning` 实现数据源开箱即用，`GF_SECURITY_ADMIN_PASSWORD=admin123` 设初始密码。

### 15.3 常用 PromQL 面板

| 面板 | PromQL | 说明 |
|------|--------|------|
| 实时 QPS | `sum by (application) (rate(http_server_requests_seconds_count[1m]))` | 每秒请求数，按服务分线 |
| P95 延迟 | `histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket[5m])))` | 需先开 percentiles-histogram |
| 429 拒绝率 | `sum(rate(http_server_requests_seconds_count{status="429"}[1m]))` | 限流生效直接可见 |
| 熔断器状态 | `resilience4j_circuitbreaker_state{name="order-circuit-breaker"}` | 0=CLOSED 1=OPEN 2=HALF_OPEN |
| JVM 堆占用 | `jvm_memory_used_bytes{area="heap"}` | 配合 compose mem_limit 调优 |

### 15.4 踩坑

- **P95 面板无数据**：只配 `percentiles`（如 `0.95: true`）得到的是客户端近似值、且 Prometheus 查不到；必须用 `percentiles-histogram: true` 生成 `_bucket` 时序，`histogram_quantile` 才有输入。
- **多副本只抓到一个**：静态 `targets` 写服务名只解析到一个 IP；改 `dns_sd_configs` A 记录才能发现全部副本。
- **指标里没有 application 标签**：`management.metrics.tags.application` 漏配，Grafana 无法按服务分组聚合。

### 15.5 RocketMQ 消息积压告警（第二十一章实现）

Prometheus 除抓取微服务基础指标（JVM、HTTP 请求、Feign 调用）外，还通过 order-service 内置的 Micrometer Gauge 监控 RocketMQ 消息积压：

| 指标 | 含义 | 来源 |
|------|------|------|
| `rocketmq_consumer_lag{consumer_group}` | 消费组积压条数（Diff Total = BrokerOffset − ConsumerOffset） | 宿主机 `lag-pusher.ps1` 每 15s 从 broker `mqadmin consumerProgress` 拉取后推送到 `/orders/internal/lag-report` |
| `rocketmq_dead_letter_pending` | 死信待补偿条数（`t_dead_letter WHERE status=0`） | order-service `MqLagMonitor` 定时查库 |

告警规则在 `docker/prometheus/alerts.yml`：`BacklogHigh`（lag>1000 持续 1m → warning）与 `DeadLetterPending`（≥5 → critical）。告警状态在 `http://localhost:9090/alerts` 实时可见。完整实现与踩坑记录见第二十一章。

---

## 十六、JMeter 压测实测

用 JMeter 5.6.3 CLI 模式对前面章节的限流/熔断做**定量验证**，四轮压测各有明确目标。脚本在 `jmeter/*.jmx`，结果 `jmeter/out-0*.jtl`，日志 `jmeter/jmeter-0*.log`。

### 16.1 运行方式

```powershell
jmeter -n -t jmeter/01-baseline-orders.jmx -l jmeter/out-01.jtl -j jmeter/jmeter-01.log
# -n 非 GUI 模式；-t 脚本；-l 结果文件；-j 日志
```

### 16.2 四轮结果

| 轮次 | 脚本 | 目标 | 请求量 | 结果分布 | 结论 |
|------|------|------|--------|----------|------|
| 01 基线 | `01-baseline-orders.jmx` | 测正常链路吞吐/延迟 | 115 | 全 200 | 链路健康，作为对照组 |
| 02 限流 | `02-ratelimit-orders.jmx` | 触发网关 Redis 令牌桶 | ~24.7 万 | 141 个 200 + 24.7 万 429 | replenishRate=2 生效，超额流量被 429 削峰 |
| 03 熔断 | `03-circuit-fail.jmx` | 触发 Resilience4j 熔断 | ~24.3 万 | 1 个 200 + 24.3 万 429 + 15 个 500 + 125 个 503 | 失败率超阈值熔断打开，fallback 接管 |
| 04 Sentinel 限流 | `04-sentinel-limt.jmx` | 触发 Sentinel 流控规则 | - | 部分 200 + 部分 429 | Sentinel QPS 规则生效（见十七章） |

### 16.3 观察要点（配合十五章面板）

- **02 限流**：Grafana「429 拒绝率」曲线在压测启动瞬间飙升，QPS 面板被压在令牌桶速率附近——证明限流在网关最外层挡住了流量，没打到下游。
- **03 熔断**：熔断器状态面板从 0(CLOSED) 跳到 1(OPEN)，等待 10s 后转 2(HALF_OPEN) 再回 0，与 Resilience4j 配置 `waitDurationInOpenState=10s` 完全吻合。
- **P95 延迟**：熔断打开后被拒绝的请求延迟从 ~187ms（真实 500）降到 ~10ms（fallback），P95 曲线明显下降——熔断不仅保护下游，还降低了调用方延迟。

### 16.4 Windows 本机压测的坑

- **PowerShell 5.1 并发出不去**：`Invoke-WebRequest` 默认每服务器仅 2 连接；改用 `System.Net.Http.HttpClient` 并设 `ServicePointManager.DefaultConnectionLimit=100`。这也是压测脚本最终用 JMeter 而非 PS 脚本的原因之一。
- **JMeter 结果分析**：`.jtl` 是 CSV，可用 JMeter GUI「聚合报告/察看结果树」打开，或命令行 `jmeter -g out-01.jtl -o report/` 生成 HTML 报告。

---

## 十七、Sentinel 全栈容器化

第七章的 Sentinel 是「依赖在微服务里 + Dashboard 本机 jar 单独跑」的半成品。本章把 Sentinel **完整搬进 Docker Compose**：Dashboard 容器化、三个微服务全部接入、网关层熔断从 Resilience4j 切换到 Sentinel。

### 17.1 架构变化

```
之前：微服务(sentinel 依赖) ──心跳──▶ 本机 jar Dashboard :8858（手动启动）
现在：微服务(sentinel 依赖) ──心跳──▶ sentinel-dashboard 容器 :8858（compose 一键起）
                                        ▲ 规则下发（Dashboard → 客户端内存）
                                        Nacos（规则持久化，重启不丢，见 7.5）
```

- Dashboard 用镜像 `bladex/sentinel-dashboard:1.8.8`（官方未发布 ARM/最新 tag，此镜像与 Spring Cloud Alibaba 2023.0.1.0 的 Sentinel 1.8.8 版本对齐）。
- 三个微服务通过环境变量 `SENTINEL_DASHBOARD: sentinel-dashboard:8858` 指向容器地址，替代原来的 `127.0.0.1:8858`。

### 17.2 接入清单

| 改动 | 文件 | 内容 |
|------|------|------|
| Dashboard 容器 | `docker-compose.yml` | 新增 `sentinel-dashboard` 服务，映射 8858，`mem_limit: 384m` |
| 微服务环境变量 | `docker-compose.yml` | 三服务加 `SENTINEL_DASHBOARD` + `depends_on: sentinel-dashboard` |
| 依赖 | 三服务 `pom.xml` | `spring-cloud-starter-alibaba-sentinel` |
| 配置 | 三服务 `application.yml` | `spring.cloud.sentinel.transport.dashboard=${SENTINEL_DASHBOARD:127.0.0.1:8858}` |
| 网关熔断切换 | `gateway-service/application.yml` | **移除** `CircuitBreaker` filter，熔断改由 Sentinel degrade 规则接管 |

### 17.3 实测：与 Resilience4j 的冲突

网关加 Sentinel 依赖后**启动直接失败**：

```
java.lang.IllegalArgumentException: Unable to find GatewayFilterFactory with name CircuitBreaker
```

**根因**：路由里配的 `CircuitBreaker` filter 由 `spring-cloud-starter-circuitbreaker-reactor-resilience4j` 提供，而 Sentinel 的 WebFlux 自动配置（`SentinelWebFluxAutoConfiguration`）与 Resilience4j CircuitBreaker 的自动装配相互干扰，导致 `CircuitBreakerGatewayFilterFactory` 没注册成功——路由引用不存在的 filter，启动即挂。

**结论**：网关层 Resilience4j CircuitBreaker filter 与 Sentinel **不能共存**，二选一。本项目选 Sentinel（动态规则 + Dashboard 可视化更强），从 order-service 路由移除 `CircuitBreaker` filter 后启动正常。

### 17.4 Sentinel vs Resilience4j 选型对比（结合本项目实测）

| 维度 | Resilience4j | Sentinel |
|------|--------------|----------|
| 规则管理 | 改 YAML 重启生效 | **Dashboard 动态实时下发，无需重启** |
| 可视化 | 仅 Grafana 指标（需自己配面板） | Dashboard 自带实时监控 + 资源链路树 |
| 规则持久化 | 配置即代码（Git 管理） | 需额外接 Nacos 数据源，否则重启丢失 |
| 网关集成 | CircuitBreaker filter 开箱即用 | 与 Resilience4j filter 冲突，需移除其一 |
| 流控能力 | 无（需配合网关限流） | QPS/并发/关联/链路流控一体 |

### 17.5 验证

- Dashboard http://localhost:8858（sentinel/sentinel）→ 机器列表出现 gateway/user/order 三个应用，全部 healthy。
- 簇点链路自动发现资源：网关侧 `/api/orders/**`、`/api/login`；order 侧 `/orders/{id}`、Feign 资源 `GET:http://user-service/users/{id}`。
- 流控：给 `/orders/{id}` 配 QPS=5，04 压测脚本快速打 20 请求 → 17 个 200 + 3 个 429（Sentinel 拦截）。
- 熔断：给 `/orders/fail` 配异常比例 ≥50%、窗口 10s，压测触发后熔断打开。

### 17.6 已知限制

`/orders/fail` 的异常比例熔断**统计不到异常**（Dashboard 显示 `exceptionQps=0`）：Spring MVC 的 `@ExceptionHandler` 捕获异常后，`HandlerInterceptor.afterCompletion` 收到的 `ex=null`，Sentinel Web MVC adapter 无法统计。要体验完整熔断，用 Feign 级降级（`UserClientFallbackFactory`）：停掉 user-service 后 order 的 Feign 调用抛异常，Sentinel 能正确统计并触发降级。

---

## 十八、Loki 日志栈：补全可观测性最后一角

十五章接了 metrics（Prometheus），13.3 节接了 tracing（Zipkin），本章补上 **logging**——可观测性三支柱至此闭环。

### 18.1 为什么是 Loki 而不是 ELK

| 维度 | ELK（ES+Logstash+Kibana+Filebeat） | Loki + Promtail |
|------|-----------------------------------|-----------------|
| 新增内存 | ~2GB（ES 一个就要 1g） | **~350MB**（loki 256m + promtail 128m） |
| 索引方式 | 全文倒排索引（日志内容全建索引） | **只索引标签**，日志正文压缩存块 |
| 查询界面 | 新增 Kibana :5601 | 复用现有 Grafana :3000 |
| 概念模型 | 三套新组件 | 与 Prometheus 标签模型一脉相承 |

本项目 WSL2 内存上限 5GB、现有容器已占 ~3.6GB，ELK 塞不下；Loki 是最优解。

### 18.2 架构与数据流

```
12 个容器 stdout/stderr
        │  （应用零侵入，不改一行代码）
        ▼
   Promtail :9080          ← 挂 /var/run/docker.sock，docker_sd 自动发现容器
        │  提取标签：service / container / stream / level
        ▼  push
   Loki :3100              ← 只索引标签，日志正文压缩存块（TSDB + filesystem）
        ▲  LogQL 查询
        │
   Grafana :3000           ← provisioning 自动注入 Loki 数据源
```

### 18.3 配置清单

| 文件 | 作用 |
|------|------|
| `docker/loki/local-config.yaml` | Loki 单节点：TSDB 索引 + filesystem 存储 + 7 天保留（`retention_period: 168h`） |
| `docker/promtail/config.yml` | docker_sd 采集全部容器日志，relabel 提取 service/container/stream 标签，pipeline 提取 level |
| `docker-compose.yml` | 新增 loki/promtail 两服务 + loki-data 卷 |
| `docker/grafana/provisioning/datasources/datasource.yml` | 加 Loki 数据源（`http://loki:3100`） |

### 18.4 LogQL 常用查询（Grafana → Explore → Loki）

```logql
{service="order-service"}                          # 某服务全部日志
{service="order-service"} |= "ERROR"               # 按关键字全文过滤
{service=~"order-service|user-service", level="ERROR"}   # 多服务只看错误
{service=~"gateway-service|order-service|user-service"} |= "6acdb2a1e9c86aba7f9caa7eeeddb811"   # 按 traceId 拉完整链路
{service="order-service"} |~ `\[order-service\]`   # 正则用反引号包裹（双引号里 \[ 是非法转义）
```

> 注意：`query_range` 默认只查最近 1 小时，GUI 里记得选时间范围；API 需显式传 `start` 参数。

### 18.5 实测验证与踩坑

**验证结果**（2026-10-13）：登录 `/api/login` 拿 token → 调 `/api/orders/fail` → Loki 中按 traceId `6acdb2a1e9c86aba…` 一次查出该请求的 WARN + ERROR 日志对，traceId 与 Zipkin 中完全一致——metrics + tracing + logging 三支柱闭环。新增内存仅 loki 193MiB + promtail 50MiB，符合 5GB WSL2 预算。

| 坑 | 现象 | 解法 |
|----|------|------|
| Promtail 首次补传积压日志被限速 | `429 Ingestion rate limit exceeded (limit: 4194304 bytes/sec)`——JMeter 压测攒了几十万行历史日志，默认 4MB/s 顶不住 | `limits_config` 调大 `ingestion_rate_mb: 16` + `ingestion_burst_size_mb: 32` |
| LogQL 正则转义 | `\[` 在双引号查询串里报 `invalid char escape` | 正则整体用**反引号**包裹：`` |~ `\[order-service\]` `` |
| 堆栈续行没有 traceId | `at xxx.run(...)` 每行是独立日志条目，traceId 只在首行 | 查 traceId 前先按 `\|= " ERROR "` 定位首行 |
| Spring Boot 3 的 traceId 格式变了 | 不是 Sleuth 的 `[app,traceId,spanId]`，而是 **`[traceId-spanId]` 独立中括号段**（破折号连接） | 查询/正则按新格式写，别再按逗号格式匹配 |
| 网关不逐条记请求日志 | 按 traceId 只能查到 order/user 的业务日志 | 正常现象；网关只在鉴权失败/降级时打日志 |

### 18.6 关键设计决策（面试高频）

- **traceId 绝不能做成标签**：每个请求一个唯一值 → 标签基数爆炸会撑爆 Loki 索引。正确做法是留在日志正文里，查询时 `|= "traceId"` 全文过滤——这正是"Loki 只索引标签"设计哲学的体现。
- **Spring Boot 3 + Micrometer Tracing 默认把 traceId/spanId 写进日志**（MDC），所以应用侧零配置就能按 traceId 串联日志。
- **Promtail 挂 Docker socket 采集，应用完全无感知**——对比 ELK 路线里应用要加 logback encoder 依赖改配置，这是云原生采集的主流姿势。

## 十九、RocketMQ 三大特性实战：顺序消息 / 延迟消息 / 消息积压

第十章覆盖了普通消息与事务消息，本章补齐剩余三大特性。先给一个总纲——**三大特性各自落在链路的不同位置**，学习时别混为一谈：

| 特性 | 落在哪一端 | 一句话原理 |
|---|---|---|
| 延迟消息 | **生产端**一次性 API | 发送时指定 delayLevel，Broker 内部延迟队列到点改写回真实 queue |
| 顺序消息 | **双端契约**（缺一不可） | 生产端 hashKey 路由进同一 queue + 消费端 ORDERLY 队列锁 |
| 消息积压 | **消费端产能** + 运维监控 | 消费上限 = 消费线程数 ÷ 单条耗时；洪峰 > 上限 ⇒ Diff Total 增长 |

三个特性各配一个可重复执行的验证脚本（`scripts/` 下）：`double-order.ps1`、`cas-race-test.ps1`、`msg-backlog.ps1`。

### 19.1 顺序消息：订单状态流转

**场景**：订单状态机 创建→已支付→打包中→已发货→配送中→已签收，同一订单必须严格按序消费；不同订单之间无序即可。

**双端各做一半**（`OrderStatusDemoController` 生产 / `OrderStatusConsumer` 消费，Topic `order-status-topic`）：

```
生产端：syncSendOrderly("order-status-topic:STATUS_CHANGE", msg, hashKey = String.valueOf(orderId))
        └─ MessageQueueSelector：queue = queues[hash(orderId) % queues.size()]
           同一 orderId 永远落在同一 queue（queue 内天然 FIFO）

消费端：consumeMode = ConsumeMode.ORDERLY
        └─ 消费端对每个 MessageQueue 加锁，同一 queue 同一时刻只有一个线程消费
           按 queue 内顺序逐条取出
```

生产端保证"同一订单的消息进同一队列"，消费端保证"队列内串行消费"——**两端配合才有序**：只做生产端分区、消费端仍 CONCURRENTLY，同一 queue 内多线程拉取照样乱序；只做消费端加锁、生产端乱路由，消息根本不在一个 queue 里。

**验证**（`double-order.ps1`）：301/302 两订单并发发送，各 6 条状态消息。实测 14 条日志：同一 orderId 的 step 严格 1→6 递增，两订单之间交错——**分区有序、全局无序**，正是顺序消息的语义。

**顺序消费的核心代价——失败处理**：

| | 并发消费 CONCURRENTLY（积分消费者） | 顺序消费 ORDERLY（状态消费者） |
|---|---|---|
| 失败后 | 进重试队列稍后重投，**不影响**其它消息 | 当前 queue 挂起（SUSPEND_CURRENT_QUEUE_A_MOMENT），原地反复重试，**后续消息全部排队等待** |
| 吞吐 | 高 | 受最慢消息拖累 |
| 适用 | 通知类、可乱序 | 状态机、账户流水等必须保序 |

模拟观察：`/orders/status-demo?orderId=301&failStep=3`——step 3 首次抛异常挂起 queue，重试放行后 4~6 才被消费，日志时间差一目了然。

### 19.2 延迟消息：订单超时自动取消

**场景**：下单后 10s 未支付自动取消（学习环境 level 3；生产用 level 14 = 10 分钟）。

**发送**挂在 Seata 下单链路的 afterCommit 里（与积分消息同点，回滚必不发）：

```java
// OrderService —— 事务提交后发延迟消息（delayLevel=3 → 10s 后投递）
rocketMQTemplate.syncSend("order-delay-topic:ORDER_DELAY_CHECK",
        MessageBuilder.withPayload(msg).build(), 3000, 3);
```

生产端是一次性 API：发完即走，**定时全由 Broker 完成**（消息先进内部 SCHEDULE_TOPIC_XXXX，到点改写回真实 queue，消费者才可见）。

**消费**（`OrderDelayConsumer`）收到后用 **CAS 条件更新**取消：

```sql
UPDATE t_order SET status = 2 WHERE id = ? AND status = 0
```

- 影响 1 行 → 10s 内未支付，成功取消
- 影响 0 行 → 已支付 / 已取消 / 订单不存在（事务回滚），跳过；重复投递天然幂等

**为什么必须 CAS（本章最值钱的一课）**：`先 SELECT 判断再 UPDATE` 是两步写法，用户可能恰好在两步之间完成支付——无条件 UPDATE 会把已支付订单**覆盖成已取消**（丢失更新）。条件 UPDATE 把判断与写入合并成一条原子语句，与 payOrder 的 `WHERE status=0` 条件更新被 MySQL 行锁互斥，**两者必有一个赢家，绝无中间态**。

**4.x 延迟级别是固定档位**（不可任意时长）：1=1s、2=5s、3=10s、4=30s、5=1m、6=2m … 14=10m；要任意时刻投递需 RocketMQ 5.x 的定时消息。

**验证**（`cas-race-test.ps1 -Rounds 30`）：每轮下单后在同一时刻并发发起 pay 与 admin-cancel，实测 30 轮 0 个"支付成功却被取消"矛盾。

### 19.3 消息积压：瞬时洪峰 vs 慢消费

**制造积压**：`BacklogDemoController` 用 `sendOneWay`（不等 Broker ACK，生产端吞吐最高）把 3000 条消息一次性灌入 Broker（实测 11494 msg/s）；`BacklogConsumer` 每条 sleep 100ms（20 线程 → 上限约 200 msg/s）。

**消费上限公式**：

```
单实例消费上限 ≈ consumeThreadNumber ÷ 单条耗时(秒) = 20 ÷ 0.1 = 200 msg/s
```

**观察**：`docker exec broker sh mqadmin consumerProgress -g order-backlog-consumer-group -n namesrv:9876`，盯 **Diff Total**（Broker 最大位点 − 消费位点 = 积压条数）冲高回落。

**验证**（`msg-backlog.ps1`）：自动登录 → 灌入 → 采样 Diff 峰值 → 等归零 → 断言 4 项：

| 指标 | 实测（默认 3000 条 / 100ms） |
|---|---|
| 生产速率（sendOneWay 洪峰） | 11494 msg/s |
| 消费上限（1 实例 × 20 线程 ÷ 0.1s） | ~200 msg/s |
| Diff Total 峰值 | 2960 ≈ 灌入量，洪峰几乎全部转为积压 |
| 追平耗时 / 实均速率 | 21.1s / ~140 msg/s |

**三种追平手段实测**（`-Count 6000`）：

| 方案 | 追平耗时 | 与公式对照 |
|---|---|---|
| 20 线程（基准） | 29.8s | 6000 ÷ 200 ≈ 30s ✓ |
| 128 线程（单实例拉满） | 4.3s | 6000 ÷ 1280 ≈ 4.7s ✓ |
| 双实例 `--scale order-service=2` | 14.9s | 每实例独立线程池，产能线性叠加：6000 ÷ 400 ≈ 15s ✓ |

**关键坑（实测踩到）**：真正生效的并发参数是 `consumeThreadNumber`（starter 2.3.0 映射为 `consumer.setConsumeThreadMin`）；`consumeThreadMax` 是**摆设**——RocketMQ 消费线程池是 ThreadPoolExecutor + **无界 LinkedBlockingQueue**，队列永远填不满 ⇒ maxPoolSize 永不扩容，实际并发恒等于 corePoolSize，只调 max 没有任何效果。

**运维视角**：
- 积压不是 bug，是**容量问题**——大促/重试风暴时生产速率 > 消费上限是常态，要的是"可观测 + 可扩容"，而不是消灭它
- 单条耗时是产能天花板：sleep 100ms 时线程拉到 128 也只有 1280 msg/s；**扩实例是线性方案，优化单条耗时才是根治**
- 监控盯 Diff Total（生产做法：Prometheus exporter + Grafana 面板告警，思路见十五章）

> 局限说明：`mqadmin` 每次执行要起一个 JVM（~3.5s），脚本采样间隔约 4s，"追平耗时"是 4s 粒度的估算值；不影响峰值抓取——生产瞬时、消费慢，Diff 峰值总能被采样到。

## 二十、RocketMQ 死信队列：可见 / 可补偿 / 不丢业务

消费失败本是常态，但"怎么处置失败消息"决定了一套 MQ 是否可用于生产。RocketMQ 给出的答案是**死信队列（DLQ）**——重试也救不回来的消息不该无限重试拖垮整个消费组，而要**隔离**出来交给人工。本章把它做成一个**可见、可补偿、不丢业务**的完整闭环。

### 20.1 死信是怎么产生的

```
生产者 → Topic → 消费者处理失败（抛异常）
        → Broker 按梯度延迟重投（10s → 30s → 1m → … → 2h，默认 16 次）
        → 重试耗尽仍失败 → Broker 自动投递到死信 Topic：%DLQ%<consumerGroup>
```

四个必须记住的事实：

| 事实 | 说明 |
|---|---|---|
| **Topic 名固定** | `%DLQ%<消费组名>`，由 Broker **自动创建**，无需人工 `updateTopic` |
| **消息体不变** | 进死信的 body 与原消息完全一致（仍是同一份 JSON 报文），可直接反序列化 |
| **不会自动重投** | 死信消息**永久停留**在死信 Topic，只能人工/程序补偿——这正是它的价值：不让毒消息无限循环 |
| **顺序消费例外** | `ORDERLY` 消费失败会**无限挂起 queue**、原地反复重试，**永远进不了死信**；毒消息场景必须用 `CONCURRENTLY` |

> 本演示复用 `BacklogConsumer`（`order-backlog-consumer-group`，CONCURRENTLY），因此死信 Topic 为 `%DLQ%order-backlog-consumer-group`。

**加速演示**：默认 16 次梯度重试最长要 2h，学习环境把 `maxReconsumeTimes` 压到 **2**（`@RocketMQMessageListener(maxReconsumeTimes = 2)`），约 10s + 30s 退避后即进死信（实测 3 条毒消息 ~45s 全部入死信）。

**毒消息制造**：`BacklogConsumer` 中 `sleepMs < 0` 即抛异常：

```java
if (sleepMs < 0) {
    throw new RuntimeException("模拟消费失败（毒消息 sleepMs=" + sleepMs + "），重试耗尽后进入死信队列");
}
```

```powershell
# 灌入 3 条毒消息
POST /api/orders/backlog-demo?count=3&sleepMs=-1
```

### 20.2 三件套实现

**① 可见——死信落库**（`DlqConsumer`，新消费组 `order-dlq-consumer-group` 订阅该死信 Topic）：

```java
@RocketMQMessageListener(topic = "%DLQ%order-backlog-consumer-group",
        selectorExpression = "*", consumerGroup = "order-dlq-consumer-group")
public class DlqConsumer implements RocketMQListener<MessageExt> { ... }
```

- 泛型用 **`MessageExt`**（而非业务 DTO）：DLQ 监听要读 `msgId` / `topic` / 属性等元数据，此时 starter **不做反序列化、直接透传原始消息**；body 作为字符串原样入库
- 落库表 `order_db.t_dead_letter`：`msg_id` / `topic` / `consumer_group` / `body`（原文）/ `reason` / `status` / `retry_count` / 时间戳

**② 可补偿——重投接口**（`DlqController`）：

```java
// POST /api/orders/dlq/retry?limit=10&sleepMs=0
读 t_dead_letter(status=0) → 反序列化 body → 修复（覆盖导致失败的 sleepMs）
→ 打上补偿标记 dlqId → 重投原 Topic → 消费者成功 → 回写 status=1
```

**③ 不丢业务——闭环回写**：重投消息带 `dlqId`，`BacklogConsumer` 消费成功后回写死信状态，把"投递成功"升级为"**业务真的处理成功**"：

```java
if (message.getDlqId() != null) {
    deadLetterMapper.updateById(new DeadLetter(){{ setId(message.getDlqId()); setStatus(1); }});
}
```

**状态机**：`0 待补偿 → 1 补偿成功`（`2 补偿失败` 预留给人工介入场景）。

### 20.3 完整闭环

```
毒消息(sleepMs=-1)
  └─ 消费抛异常 ─→ Broker 梯度重试(2 次) ─→ 耗尽 → 自动投递 %DLQ%order-backlog-consumer-group
        └─ DlqConsumer 落库 t_dead_letter(status=0, body 原样)      ← 可见 / 持久(不丢业务)
              └─ POST /api/orders/dlq/retry 修复后重投              ← 可补偿
                    └─ BacklogConsumer 消费成功 → 回写 status=1     ← 闭环
```

### 20.4 验证（`scripts/dlq-test.ps1`）

```powershell
.\scripts\dlq-test.ps1 -Count 3   # 退出码 = 失败断言数，全绿为 0
```

自动完成：登录 → 灌毒消息 → 轮询死信落库 → 查 Broker 侧死信 Topic → 调补偿重投 → 轮询回写 → **9 项断言**。实测结果：

| 断言 | 实测 |
|---|---|
| 可见：毒消息全部落入死信并落库 | 新增 3/3 |
| 可见：Broker 死信 Topic 同步增长 | MaxOffset 3 → 6 |
| 可见：死信消费组已消费（Diff=0，全部落库） | Diff Total = 0 |
| 不丢业务：原始报文完整保存 | body 含 `"sleepMs":-1` 3/3 |
| 可见：来源 topic / 消费组记录正确 | 3/3 |
| 补偿前：新增记录处于待补偿 | status=0 3/3 |
| 可补偿：重投接口读库并成功重投 | resent 3/3，失败 0 |
| 可补偿闭环：消费成功并回写 status=1 | 3/3（<0.1s） |
| 可追溯：累计重投次数 | retryCount≥1 3/3 |

**关键坑（实测）**：

| 坑 | 现象 | 解法 |
|---|---|---|
| ORDERLY 消费不进死信 | 顺序消费失败会无限挂起 queue | 毒消息必须用 CONCURRENTLY 消费者 |
| `%DLQ%` 用别的组消费 | 需独立消费组订阅该死信 Topic | 本演示用 `order-dlq-consumer-group`，与业务组隔离 |
| `reconsumeTimes` 进死信后归零 | DLQ 消息上的该字段已被 Broker 重置为 0 | 重试次数别指望从死信消息上读，按配置的 `maxReconsumeTimes` 说明即可 |
| `%` 号主题名 | 命令行传参不要多加引号导致 sh 误解析 | `mqadmin topicStatus -t '%DLQ%<组名>' -n namesrv:9876` |

**运维视角**：
- 死信 = "**隔离**"而非"丢弃"：DLQ 数量应作为核心告警指标（Prometheus exporter + Grafana 盯死信增长速率）
- 生产建议：死信消费组只做**落库/告警**，**不要自动无脑重投**（原消息大概率仍会失败，只会把死信搬回主流程）；重投应走人工确认或修数后触发
- 本演示的 `sleepMs` 覆盖即"人工修数"的最小模拟：真实场景要修的是缺失字段、外部依赖返回值等

---

## 二十一、消息积压告警：Prometheus + Micrometer Gauge

在第十五章 Prometheus 基础设施之上，给 RocketMQ 消费组加**积压告警**，实现"可见、可补偿、不丢业务"的延伸——不仅死信要告警，普通消息积压也要告警。

### 21.1 核心指标

| 指标 | 含义 | 数据源 |
|------|------|--------|
| `rocketmq_consumer_lag{consumer_group="<组名>"}` | 消费组积压条数（Diff Total = BrokerOffset − ConsumerOffset） | broker `mqadmin consumerProgress` |
| `rocketmq_dead_letter_pending` | 死信待补偿条数（`t_dead_letter WHERE status=0`） | order-service `DeadLetterMapper` 查库 |

两个指标都以 Micrometer Gauge 形式暴露在 `order-service/actuator/prometheus`，被 Prometheus 15s 抓取一次。

### 21.2 架构与数据流

```
┌─────────────┐     docker exec      ┌─────────────────┐     POST /orders/internal/lag-report
│  broker 容器 │  ←—— mqadmin ————→  │ lag-pusher.ps1   │  ─────────────────────────────→
│  (mqadmin)   │   consumerProgress  │  (宿主机 15s 轮询) │                           │
└─────────────┘                     └─────────────────┘                           ↓
                                                                     ┌──────────────────────┐
                                                                     │  order-service       │
                                                                     │  ├─ MqLagMonitor     │
                                                                     │  │   ├─ lagCache    │←── report() 更新
                                                                     │  │   └─ Gauge 注册  │←── 启动时注册
                                                                     │  └─ LagReportCtrl   │
                                                                     └──────────┬───────────┘
                                                                                │ scrape 15s
                                                                                ↓
                                                                     ┌──────────────────────┐
                                                                     │   Prometheus         │
                                                                     │   alerts.yml 规则评估 │
                                                                     │   BacklogHigh firing  │
                                                                     └──────────────────────┘
```

### 21.3 实现清单

| 文件 | 作用 |
|------|------|
| `order-service/config/MqLagMonitor.java` | Gauge 注册中心：`lagCache` Map 存消费组 → Diff Total；`registerLagGauge()` 幂等注册；死信 gauge 闭包直接查 DB |
| `order-service/controller/LagReportController.java` | `/orders/internal/lag-report` 接收宿主机推送；gateway 白名单放行 `/api/orders/internal/` |
| `scripts/lag-pusher.ps1` | 宿主机脚本：每 15s `docker exec broker mqadmin consumerProgress -g <组>` 解析 `Diff Total:` 行，POST 到 lag-report |
| `docker/prometheus/alerts.yml` | 告警规则：`BacklogHigh`（lag>1000 持续 1m → warning）、`DeadLetterPending`（≥5 → critical） |
| `docker/prometheus/prometheus.yml` | 加 `rule_files: ["alerts.yml"]` |
| `docker-compose.yml` | prometheus service 加 `./docker/prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro` |

### 21.4 踩坑记录（比实现更值钱）

| 坑 | 现象 | 解法 |
|---|---|---|
| **rocketmq-tools 5.1.x 协议不兼容 broker 4.9.7** | Java 侧 `DefaultMQAdminExtImpl` 发 request type 351（5.x 协议），broker 4.9.7 返回 `request type 351 not supported` | 放弃 Java 主动拉取，改宿主机脚本 exec broker 自带 mqadmin（版本天然匹配）+ 推送到 Java 侧 |
| **内部接口被 Gateway JWT 拦截** | lag-pusher.ps1 POST 无 Bearer Token，Gateway 返回 401 | gateway `application.yml` 白名单加 `/api/orders/internal/` 前缀 |
| **Gauge 重复注册** | Micrometer 不允许同名 Gauge 重复注册 | `registered` ConcurrentHashMap 幂等控制；启动时先注册已知消费组，动态发现时补注册 |
| **lagCache 更新不及时** | Gauge 闭包读的是 lagCache 旧值 | lag-pusher.ps1 每 15s 推送，Prometheus 15s scrape，最大延迟 30s 内 |

### 21.5 验证

```powershell
# 1. 启动 lag-pusher（宿主机后台）
.\scripts\lag-pusher.ps1 -IntervalSec 15

# 2. 触发积压
.\scripts\msg-backlog.ps1 -Count 3000 -SleepMs 500

# 3. 观察 Prometheus 指标（localhost:9090）
rocketmq_consumer_lag{consumer_group="order-backlog-consumer-group"}  # 从 0 跳到 3000，随后回落

# 4. 观察告警状态（localhost:9090/alerts）
BacklogHigh  state=firing   # lag>1000 持续 1m 后触发

# 5. 积压清完 → 告警自动恢复
BacklogHigh  state=inactive # Diff Total 归零后约 1m 自动消失
```

实测：3000 条 × 500ms sleep 灌入后，lag 从 0 → 3000 → 逐步回落到 0；BacklogHigh 告警 firing → 积压清完 → 自动 resolved，全链路闭环。

### 21.6 生产建议

- **lag-pusher.ps1 启动方式**：学习环境用 `Start-Process pwsh -WindowStyle Hidden` 或 `Start-Job`；生产建议注册 Windows 任务计划程序（开机自启）或 docker compose 加 sidecar 容器（带宿主机 docker socket）
- **Alertmanager**：当前只配了 Prometheus 内置告警 UI（`/alerts` 页面）；生产需加 Alertmanager 配 webhook/email/pagerduty 通知
- **阈值调优**：`BacklogHigh` 的 `>1000` 是演示阈值；生产按业务量调整（大流量消费组可设 `>50000`，低流量 `>100`）
- **死信告警联动**：`DeadLetterPending` 与 `BacklogHigh` 同时触发时，优先排查死信（死信消费组通常只做落库，不主动重投）

## 二十二、CI/CD：GitHub Actions 构建 → 阿里云 ACR → 本机自动部署

### 22.1 为什么需要 CI/CD

前面二十一章下来，镜像的产出方式一直是**本机手工 `docker build`**，这留下三个真实痛点：

| 痛点 | 具体表现 |
| --- | --- |
| 镜像没有版本化落点 | 镜像只躺在本机 Docker 里，换机器、清缓存、`docker image prune` 之后就没了；也没法回答"现在跑的镜像是哪次代码构建的" |
| 没有质量闸门 | 改完代码直接 build 就上，过程中没有任何自动断言，第二十一章的 Gauge 逻辑回归了也只能靠肉眼发现 |
| 部署全靠手敲 | `docker build` 三个服务 + `docker compose up -d` 全靠记忆，容易漏服务、记错参数 |

本章要打通的最小闭环：

```
push 到 master
   │
   ▼
① CI（GitHub 托管 runner, ubuntu-latest）
   checkout → JDK17 + Maven 缓存 → mvn -B clean package（含单测）
   → docker build × 3 → docker push × 3
   │
   ▼
② 阿里云 ACR（镜像仓库，每次 CI 推一个以 commit 哈希命名的不可变 tag，历史版本全保留）
   │
   ▼
③ CD（本机 self-hosted runner，手动 Run workflow）
   docker login → compose pull × 3 → compose up -d --force-recreate × 3
   → 探针验证（网关 health + order-service 路由）
   │
   ▼
④ 本机 Docker Desktop 上三个微服务容器换成新镜像（基础设施容器不动）
```

### 22.2 本章新增/改动的文件

| 文件 | 作用 |
| --- | --- |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | CI：构建 + 单测 + 推镜像到 ACR |
| [.github/workflows/deploy.yml](.github/workflows/deploy.yml) | CD：本机 runner 拉镜像 + 重建容器 + 探针验证 |
| [docker-compose.deploy.yml](docker-compose.deploy.yml) | 部署覆盖文件：只把三个微服务的 `image` 指向 ACR |
| [order-service/pom.xml](order-service/pom.xml) | 加 `spring-boot-starter-test`（test 作用域） |
| [MqLagMonitorTest.java](order-service/src/test/java/com/study/cloud/order/config/MqLagMonitorTest.java) | 项目第一个单元测试 |

### 22.3 CI：构建 + 单测 + 推镜像

触发时机：`push` 到 master / `pull_request` / 手动 `workflow_dispatch`。PR 事件只跑构建和单测，**不推镜像**（来自 fork 的 PR 拿不到 Secrets，硬推会报认证失败）。

```yaml
      - name: 构建 + 单元测试
        run: mvn -B clean package          # 不写 -DskipTests，测试失败即构建失败
```

三个刻意的设计取舍：

1. **`mvn -B clean package` 不跳过测试**——这就是质量闸门。`spring-boot-starter-parent` 已托管 surefire，默认扫 `*Test.java`，`package` 阶段自动跑，失败直接让 CI 变红，不需要任何额外插件配置。
2. **单 job 顺序执行，不用 matrix + artifact 传递**：order-service / user-service 都依赖 common，`mvn` 必须整仓一次 reactor 构建，拆 matrix 会把整仓构建重复 3 次；三个 jar 合计约 360MB，artifact 上传再下载比直接复用磁盘产物更慢。同 job 内 mvn 产出的 jar 就落在各服务 `target/` 下，`docker build` 直接吃。
3. **构建上下文必须是服务自己目录**：Dockerfile 写的是 `COPY target/<artifactId>-1.0.0.jar app.jar`，所以 `docker build -t <镜像> ./user-service`，不能拿仓库根当上下文。

镜像坐标用带默认值的 env，**只配 2 个 Secret 也能先跑起来**：

```yaml
env:
  ACR_REGISTRY: ${{ vars.ACR_REGISTRY || 'registry.cn-hangzhou.aliyuncs.com' }}
  ACR_NAMESPACE: ${{ vars.ACR_NAMESPACE || 'spring-cloud-study' }}
```

### 22.4 CD：本机 self-hosted runner 部署

只支持**手动触发**（Actions 页面 → Run workflow）：学习期不自动部署，避免往 master 一 push 就把本机正在跑的一套容器全换掉。

触发时**必填** `image_tag`（无默认值，防止"忘填就部署了旧版"）。tag = CI 构建时的 commit 完整哈希（**40 位**），三处任取：`git log -1 --format=%H`、GitHub 仓库 commit 列表、ACR 控制台镜像版本列表。**回滚 = 填历史 commit 哈希再跑一次 CD**。

⚠️ 在 ACR 控制台复制时**分清两列**：「**镜像版本**」是 tag（40 位，填这个）；「**Digest / 摘要**」是镜像内容的 SHA-256（64 位，**不能**当 tag 用——`image:tag` 语法不认它，pull 会报 `not found`，本章实测踩过）。

部署覆盖文件只写 image，其余键全部继承根 compose（多 `-f` 合并时后面的覆盖标量、未写的键一律沿用）：

```yaml
services:
  user-service:
    image: "${ACR_REGISTRY:?请先设置环境变量 ACR_REGISTRY}/${ACR_NAMESPACE:?...}/user-service:${IMAGE_TAG:?请先设置环境变量 IMAGE_TAG}"
```

**为什么不开一刀直接改根 `docker-compose.yml`**：根文件里的 `image: user-service:1.0.0` 是 12.2 节本机开发流程（`docker build` + `up`）依赖的，改成 ACR 地址会让本机开发也必须先 `docker login` 再 `pull`。覆盖文件把"本地开发"和"从仓库部署"两条路彻底分开。

核心命令：

```powershell
# 只拉这三个（不带 --include-deps，不会去动 mysql/nacos 那堆基础设施镜像）
docker compose -f docker-compose.yml -f docker-compose.deploy.yml pull user-service order-service gateway-service

# 重建（--force-recreate 兜底：确保真按新拉的镜像重建容器，而不是复用旧容器）
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --force-recreate user-service order-service gateway-service
```

探针验证为什么用内部接口而不是只查 health：

```powershell
# 该路径在网关白名单里（无需 JWT），body 传空对象 {} 会走早退分支、不改任何状态
POST http://localhost:9000/api/orders/internal/lag-report   body: {}
```

一次请求同时证明三件事：**网关已起来 + Nacos 注册成功 + order-service 经网关路由通**。单查 `/actuator/health` 只能证明网关自身活着，下游没注册也照样 UP。脚本最多轮询 30 次 × 10s（服务重启后要等 Nacos 注册才通），失败时打印 `docker compose ps` 并 `throw`，让 job 明确变红。

**E2E 全链路验证与失败自动回滚**（探针之后的第二道闸门）：

探针只证明"服务起来了"，不能证明"业务是对的"。CD 在探针通过后再跑 `.\scripts\verify-full-chain.ps1`（16 节真实断言：登录/鉴权/Seata/MQ/缓存/幂等/链路/限流/RBAC），脚本结尾 `exit $fail`，任一断言失败即非零退出 → step 变红。注意此时新版本已经在真实跑，E2E 会真实下单扣款（脚本开头自动把张三账户重置为 1000/1000，可重复执行）。

自动回滚的锚点取自**部署前那一刻正在运行的旧版本**——拉新镜像之前先用 `docker ps` 读 user-service 容器的镜像 tag 写入 `$GITHUB_ENV`（`PREV_IMAGE_TAG`），后续 `recreate` / `probe` / `e2e` 三个带 `id` 的 step 任一 `outcome == 'failure'` 就触发回滚 step：把 `IMAGE_TAG` 换回旧 tag 重建容器，再做一轮轻量探针（网关 health UP 即过，旧版本是验证过的稳定版，不再跑全量 E2E）。

两个设计取舍：

- **拉镜像失败不触发回滚**：环境还没被碰过，旧容器仍在正常服务，回滚反而是多余重启。
- **失败状态是粘性的**：回滚 step 执行成功也不会把 job 洗绿（前面失败的 step 已经红了），必须人工看过失败日志、决定下一步，这正是一次真实事故应有的处理方式。

首次部署没有旧容器时锚点为空，回滚 step 会打印提示并跳过（此时只能人工排查）。E2E 自身耗时约 3~4 分钟（事务消息等 20s、熔断冷却 11s×2、令牌桶 10s×2），CD 总时长从 2~3 分钟升到 **6~8 分钟**。

### 22.5 外部前置（只有本机能做）

```powershell
# ① GitHub 建仓后接上远端并推送（工作区已有未提交改动，先 commit）
git remote add origin https://github.com/<账号>/<仓库名>.git
git add .
git commit -m "feat: 第22章 CI/CD（workflows + 部署覆盖文件 + MqLagMonitor 单测）"
git push -u origin master
```

**workflow 文件必须在默认分支上，手动 Run workflow 才可用。**

② **阿里云 ACR**：创建**个人版**实例 → 建命名空间（如 `spring-cloud-study`）→ 「访问凭证」里**设置固定密码**（个人版不支持临时 token）→ 复制控制台给出的登录命令。

> 域名形态要注意：2024-09-09 之后新建的个人版实例是 `crpi-xxxx.cn-hangzhou.personal.cr.aliyuncs.com` 这种形态，老实例才是 `registry.cn-hangzhou.aliyuncs.com`。**一律以控制台复制的登录命令为准，不要照抄本文档。**

③ **GitHub 仓库配置**（Settings → Secrets and variables → Actions）：

| 类型 | 名称 | 说明 |
| --- | --- | --- |
| Secret | `ACR_USERNAME` | 镜像仓库登录名（阿里云账号全名，或去掉 `@xxx.onaliyun.com` 的部分） |
| Secret | `ACR_PASSWORD` | 上面设置的**固定密码** |
| Variable（可选） | `ACR_REGISTRY` | 不配则用 workflow 内置的 `registry.cn-hangzhou.aliyuncs.com` |
| Variable（可选） | `ACR_NAMESPACE` | 不配则用 workflow 内置的 `spring-cloud-study` |

④ **注册 self-hosted runner**（管理员权限 pwsh；token 在 仓库 Settings → Actions → Runners → New runner 生成，**1 小时有效**）：

```powershell
.\config.cmd --url https://github.com/<账号>/<仓库名> --token <token> --name pc-spring-cloud --labels spring-cloud
.\run.cmd      # 前台运行
```

先用 `.\run.cmd` 前台跑：环境变量与 PATH 和手动改执行完全一致，排查最省事，也符合"不把脚本固化成常驻任务"的偏好。确认稳了再考虑 `.\svc.cmd install` 注册成开机自启的 Windows 服务（以 LocalSystem 身份跑，Docker Desktop 的 npipe 可达性需要实跑确认）。**Docker Desktop 必须处于运行状态**，否则部署 job 第一步 `docker version` 就会失败。

### 22.6 验证

```powershell
# ① 本地单测（不需要任何外部依赖，纯内存 Gauge 断言）
mvn -B -pl order-service -am test
#   期望：Tests run: 1, Failures: 0, Errors: 0

# ② 亲眼确认闸门有效：把断言里的 42 改成 43，再跑一次 → 本地变红
#    CI 上同理变红。改回来即可。（这是本章最直观的收获）

# ③ CI：push 后在 Actions 页面看到 job 绿，ACR 控制台三个仓库各出现一个以 commit 哈希命名的 tag

# ④ CD：先确认 runner 在线（run.cmd 在跑）、Docker Desktop 已启动
#    再在 Actions 页面手动 Run workflow → 日志里应依次出现：记录旧版本锚点 →
#    拉镜像 → 容器 Recreated → 探针通过 → E2E 16 节全过，总时长约 6~8 分钟
#    （可选演练：故意推一个坏版本，观察 recreate/probe/e2e 失败后自动回滚到旧 tag）

# ⑤ 部署后回归
docker compose ps                                  # 仍是 15 个容器 running
#   且没有出现第二套重名容器（说明 COMPOSE_PROJECT_NAME 生效）
POST http://localhost:9000/api/login               # {"username":"张三","password":"123456"}
. .\scripts\verify-full-chain.ps1                  # 全链路（注意网关限流约 2 QPS，429 要退避重试）
```

### 22.7 踩坑记录

- **`COMPOSE_PROJECT_NAME` 必须固定为 `spring-cloud-study`**（本章最坑的一个）。compose 的项目名默认取**当前目录名**，而 runner 的目录名 = GitHub 仓库名。名字对不上就会起出**第二套容器**，旧容器还占着 9000 端口，新网关直接起不来。本机 `docker compose ls` 实测现有项目名就是 `spring-cloud-study`，所以部署 job 里显式钉死它。
- **`target/` 被 `.gitignore` 忽略**：CI checkout 后没有 jar，所以 `mvn package` 必须排在 `docker build` 前面，不能只 build 不编译。
- **绝对不要加 `.dockerignore` 把 `target/` 排除掉**：Dockerfile 的 `COPY target/xxx.jar` 会直接构建失败。当前项目没有 `.dockerignore` 就是正确状态。
- **不可变 tag 取代固定 tag `1.0.0`**（本章后期升级）：早期 CI 固定推 `1.0.0`，ACR 上同名 tag 互相覆盖，无法回滚。现已改为 `IMAGE_TAG: ${{ github.sha }}`（ci.yml），每次 push 的镜像 tag = commit 完整哈希，永不覆盖、历史版本天然保留；CD 的 `image_tag` 输入同步改为**必填无默认值**，部署/回滚都是填一个 commit 哈希。
- **PR 事件不推镜像**：fork 的 PR 拿不到 Secrets，登录 ACR 必然失败，所以推流步骤加了 `if: github.event_name != 'pull_request'`。
- **`image_tag` 别填成 Digest**（实测踩过，CD run #2 失败）：ACR「镜像版本」页面有两列像哈希的东西——「镜像版本」列是 tag（40 位 commit 哈希，填这个），「Digest」列是镜像内容的 SHA-256（64 位，`image:tag` 语法不认，pull 报 `not found`）。
- **自动回滚的锚点必须取自"部署前正在跑的容器"，而不是"上一次成功部署的 tag"**：后者要靠外部存储（文件/artifact）记账，多一层状态就多处错的可能；前者直接 `docker ps` 读 user-service 容器当前的镜像 tag 写入 `$GITHUB_ENV`，环境自己就是唯一事实源。代价是首次部署锚点为空，此时回滚 step 打印提示跳过、只能人工排查。
- **回滚触发条件按 step `outcome` 而不是 `failure()`**：`recreate` / `probe` / `e2e` 各带 `id`，条件 `steps.<id>.outcome == 'failure'` 任一成立才回滚。刻意把"拉取镜像"排除在外——它失败时环境没被动过，回滚是多余重启。
- **回滚成功 job 仍然是红的（失败粘性）**：GitHub Actions 里一个 step 失败，job 最终状态就是失败，后面的 step 全绿也洗不掉。这不是缺陷而是特性——自动回滚恢复的是服务，不是事故记录，必须人工看过日志才能消红。想精确区分"部署失败但已回滚"与"回滚也失败"，看回滚 step 的日志即可（后者会 `throw` 并打印"请立即人工介入"）。
- **action 主版本号**：workflow 里用的是查证过的最新主版本（`checkout@v6` / `setup-java@v5` / `login-action@v4`）。如果首次运行报 "unable to resolve action"，把该 action 降一个大版本（v4/v3）即可，语法完全不变。
- **GitHub 托管 runner 没有本地 Maven 镜像加速**：第一次构建约 3~6 分钟，靠 `cache: maven` 缓存 `~/.m2` 缓解。

### 22.8 生产建议

- **接 Alertmanager**：第二十一章遗留项，告警目前只在 Prometheus `/alerts` 页面可见，生产需要 webhook/邮件/电话通知。
- **多环境**：用 GitHub Environments + 各自的 Secrets/Variables 区分 dev/staging/prod，部署 job 加 `environment:` 与人工审批。
