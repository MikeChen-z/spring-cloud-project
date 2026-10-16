# Spring Cloud 全链路验证脚本（统一返回体 Result 契约版）
# 用法：在项目根目录执行  .\scripts\verify-full-chain.ps1
#       （脚本只依赖 HTTP/docker exec，放 scripts/ 下从任意目录运行均可）
# 前置：docker compose up -d --scale user-service=2 --scale order-service=2，
#       全部容器就绪（约 1 分钟，双副本共 12 个容器）
#
# 覆盖：登录 / 参数校验(@Valid+@Validated) / 网关鉴权 /
#       Seata AT 提交与回滚 / RocketMQ 普通消息+事务消息 / Redis 缓存 /
#       接口幂等(Redis 防重令牌) / 链路追踪(X-Trace-Id + Zipkin) /
#       Sentinel 流控(Nacos QPS=5)与异常比例熔断(容器内直连测)
# 断言全部基于"请求前后数据库差值"；脚本开头会把张三账户重置为余额/积分各 1000，可无限次重复执行
# 运行环境：PowerShell 7 (pwsh)——默认全链路 UTF-8，无需 PS 5.1 的编码补丁

$baseUrl = "http://localhost:9000"
$pass = 0; $fail = 0

function Write-Step($msg) { Write-Host "`n========== $msg ==========" -ForegroundColor Cyan }
function Check($name, $cond, $detail) {
    if ($cond) { Write-Host "  [PASS] $name  $detail" -ForegroundColor Green; $script:pass++ }
    else { Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red; $script:fail++ }
}

# 发 HTTP 请求，统一返回 @{status; json; headers}
# -SkipHttpErrorCheck：4xx/5xx 不抛异常，返回原始状态码交给断言判断
# $extraHeaders：额外请求头（如幂等接口的 Idempotent-Token）
# $bodyObj 传字符串时原样作为 JSON 体发送（用于构造坏 JSON 验证 400）；传 hashtable 时转 JSON
# headers 归一化为单值字典：$r.headers["X-Trace-Id"] 直接拿到字符串（pwsh 原生值是集合）
function Send-Request($method, $url, $bodyObj, $token, $extraHeaders) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($extraHeaders) { foreach ($k in $extraHeaders.Keys) { $headers[$k] = $extraHeaders[$k] } }
    $p = @{ Uri = $url; Method = $method; Headers = $headers; ErrorAction = "Stop" }
    if ($null -ne $bodyObj) {
        $p.Body = if ($bodyObj -is [string]) { $bodyObj } else { $bodyObj | ConvertTo-Json -Compress }
        $p.ContentType = "application/json"
    }
    $resp = Invoke-WebRequest @p -SkipHttpErrorCheck
    $respHeaders = @{}
    foreach ($k in $resp.Headers.Keys) { $respHeaders[$k] = @($resp.Headers[$k])[0] }
    $json = if ($resp.Content) { $resp.Content | ConvertFrom-Json } else { $null }
    return @{ status = [int]$resp.StatusCode; json = $json; headers = $respHeaders }
}

# 查 MySQL 单值（-N 去表头 -B tab 分隔）
function Get-DbValue($sql) {
    return ((docker exec mysql mysql -uroot -p123456 -N -B -e $sql 2>$null | Select-Object -First 1).Trim())
}

# ========== 0. 基线准备（Seata 预热 + 重置测试账户）==========
Write-Step "0. 基线准备（Seata 预热 + 重置测试账户）"

# 先静默登录拿 token（正式的登录断言在第 1 节，重复登录无副作用）
$r = Send-Request "POST" "$baseUrl/api/login" @{ username = "张三"; password = "123456" } $null
$token = $r.json.data.accessToken
$refreshToken = $r.json.data.refreshToken

# Seata 链路预热：长时间空闲后首条全局事务要重建 TC 通道（注册分支事务/undo_log 首写），
# 可能耗时超过网关 order 路由 2s 响应超时 → 504（后端事务仍会完成扣款）。
# 用 1 元小额下单焐热；下方随即将账户重置为 1000，预热不污染后续差值断言。
Write-Host "  Seata 链路预热（1 元下单，防冷启动 504 干扰第 4 节）..." -ForegroundColor DarkGray
$null = Send-Request "POST" "$baseUrl/api/orders/seata/create?userId=1&amount=1" $null $token
Start-Sleep -Seconds 2   # 等预热单的积分消息消费完再重置，避免迟到消息把积分顶高

# 每轮脚本净扣减 1+100+50+66=217 元，反复执行会因"余额不足"误失败；
# 开跑前把测试账户重置到固定基线（断言全部基于本轮差值，重置不影响结论）
$null = docker exec mysql mysql -uroot -p123456 -e "UPDATE study_cloud.t_user SET balance=1000, points=1000 WHERE id=1;" 2>$null
$balance0 = [decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")
$points0 = [decimal](Get-DbValue "SELECT points FROM study_cloud.t_user WHERE id=1")
$orders0 = [int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")
Write-Host "  余额=$balance0  积分=$points0  订单数=$orders0"

# ========== 1. 登录与参数校验 ==========
Write-Step "1. 登录与参数校验"

$r = Send-Request "POST" "$baseUrl/api/login" @{ username = "张三"; password = "123456" } $null
Check "正常登录 200" ($r.status -eq 200 -and $r.json.data.accessToken) "code=$($r.json.code)"
$token = $r.json.data.accessToken
$refreshToken = $r.json.data.refreshToken

$r = Send-Request "POST" "$baseUrl/api/login" @{ username = ""; password = "123456" } $null
Check "空用户名 400" ($r.status -eq 400 -and $r.json.code -eq 400) $r.json.message

$r = Send-Request "POST" "$baseUrl/api/login" @{ username = "张三" } $null
Check "缺密码字段 400" ($r.status -eq 400 -and $r.json.code -eq 400) $r.json.message

$r = Send-Request "POST" "$baseUrl/api/login" @{ username = "张三"; password = "wrong" } $null
Check "密码错误 401" ($r.status -eq 401 -and $r.json.code -eq 401) $r.json.message

$r = Send-Request "POST" "$baseUrl/api/login" '{username:张三}' $null
Check "坏 JSON 400（不再是 500）" ($r.status -eq 400 -and $r.json.code -eq 400) $r.json.message

# ========== 2. 网关鉴权 ==========
Write-Step "2. 网关 JWT 鉴权"

$r = Send-Request "GET" "$baseUrl/api/orders/1" $null $null
Check "无 Token 401" ($r.status -eq 401) ""

$r = Send-Request "GET" "$baseUrl/api/orders/1" $null $token
Check "带 Token 200 + Feign 跨服务" ($r.status -eq 200 -and $r.json.data.user) "source=$($r.json.data.user.source)"

# ========== 3. 下单参数校验 ==========
Write-Step "3. 下单接口参数校验（@Validated + @NotNull/@DecimalMin）"

$r = Send-Request "POST" "$baseUrl/api/orders/seata/create?userId=1&amount=0" $null $token
Check "金额为 0 -> 400" ($r.status -eq 400 -and $r.json.code -eq 400) $r.json.message

$r = Send-Request "POST" "$baseUrl/api/orders/seata/create?amount=10" $null $token
Check "缺 userId -> 400" ($r.status -eq 400 -and $r.json.code -eq 400) $r.json.message

$r = Send-Request "POST" "$baseUrl/api/orders/seata/create?userId=abc&amount=10" $null $token
Check "userId 类型错 -> 400" ($r.status -eq 400 -and $r.json.code -eq 400) $r.json.message

# ========== 4. Seata AT 正常提交 ==========
Write-Step "4. Seata AT 分布式事务 —— 正常提交（下单 100）"

$r = Send-Request "POST" "$baseUrl/api/orders/seata/create?userId=1&amount=100" $null $token
Check "提交接口 200" ($r.status -eq 200 -and $r.json.data.success) "orderId=$($r.json.data.orderId)"
Start-Sleep -Seconds 3   # 等普通积分消息（OrderPointsConsumer，order-topic）消费
$balance1 = [decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")
$orders1 = [int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")
$points1 = [decimal](Get-DbValue "SELECT points FROM study_cloud.t_user WHERE id=1")
Check "余额扣减 100" ($balance1 -eq $balance0 - 100) "$balance0 -> $balance1"
Check "订单数 +1" ($orders1 -eq $orders0 + 1) "$orders0 -> $orders1"
Check "普通消息积分 +100" ($points1 -eq $points0 + 100) "$points0 -> $points1（Seata afterCommit 普通消息）"

# ========== 5. Seata AT 回滚 ==========
Write-Step "5. Seata AT 回滚（forceFail=true，余额/订单/积分均不应变化）"

$r = Send-Request "POST" "$baseUrl/api/orders/seata/create?userId=1&amount=100&forceFail=true" $null $token
Check "forceFail 返回 500" ($r.status -eq 500 -and $r.json.code -eq 500) $r.json.message
Start-Sleep -Seconds 2
$balance2 = [decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")
$orders2 = [int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")
Check "回滚后余额不变" ($balance2 -eq $balance1) "仍为 $balance2"
Check "回滚后订单数不变（afterCommit 不触发）" ($orders2 -eq $orders1) "仍为 $orders2"

# ========== 6. RocketMQ 事务消息 ==========
Write-Step "6. RocketMQ 事务消息（下单 50，积分最终 +50）"

$r = Send-Request "POST" "$baseUrl/api/orders/rocketmq/create?userId=1&amount=50" $null $token
Check "事务消息发送 200" ($r.status -eq 200 -and $r.json.data.success) ""
# 事务消息要经 Broker 回查确认后消费者才可见，实测确认延迟约 10~15 秒，留足 20 秒
Write-Host "  等待 Broker 事务确认 + 消费（20 秒）..." -ForegroundColor DarkGray
Start-Sleep -Seconds 20
$points2 = [decimal](Get-DbValue "SELECT points FROM study_cloud.t_user WHERE id=1")
Check "事务消息积分 +50" ($points2 -eq $points1 + 50) "$points1 -> $points2"

# ========== 7. Redis 缓存 ==========
Write-Step "7. Redis 缓存（连调两次，key 应存在）"

$null = Send-Request "GET" "$baseUrl/api/orders/1" $null $token
$null = Send-Request "GET" "$baseUrl/api/orders/1" $null $token
$cacheKey = (docker exec redis redis-cli EXISTS "orderCache::users::1" 2>$null).Trim()
Check "缓存 key 存在" ($cacheKey -eq "1") "EXISTS orderCache::users::1 = $cacheKey"

# ========== 8. 接口幂等（Redis 防重令牌 + Lua 原子消费）==========
Write-Step "8. 接口幂等（先取令牌，同一令牌只能成功下单一次）"

# 幂等下单不发积分消息，只需比对余额与订单数
$idemBalance0 = [decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")
$idemOrders0 = [int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")

$r = Send-Request "GET" "$baseUrl/api/orders/idempotent/token" $null $token
Check "获取幂等令牌 200" ($r.status -eq 200 -and $r.json.data.token) "TTL=$($r.json.data.expiresInSeconds)s"
$idemToken = $r.json.data.token
$idemKey = "idem:seata:$($idemToken.Replace('-',''))"
$redisTtl = [int]((docker exec redis redis-cli TTL $idemKey 2>$null | Select-Object -First 1).Trim())
Check "令牌已写入 Redis（TTL 约 300s）" ($redisTtl -gt 250) "TTL=$redisTtl key=$idemKey"

$r = Send-Request "POST" "$baseUrl/api/orders/seata/create-idem?userId=1&amount=66" $null $token @{ "Idempotent-Token" = $idemToken }
Check "携令牌首次下单 200" ($r.status -eq 200 -and $r.json.data.success) "orderId=$($r.json.data.orderId)"
Start-Sleep -Seconds 1
Check "首次下单余额 -66" (([decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")) -eq $idemBalance0 - 66) ""
Check "首次下单订单 +1" (([int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")) -eq $idemOrders0 + 1) ""
Check "令牌消费后已删除" (([int]((docker exec redis redis-cli EXISTS $idemKey 2>$null | Select-Object -First 1).Trim())) -eq 0) "EXISTS=0（Lua get+del 原子完成）"

$r = Send-Request "POST" "$baseUrl/api/orders/seata/create-idem?userId=1&amount=66" $null $token @{ "Idempotent-Token" = $idemToken }
Check "同一令牌重复提交 400" ($r.status -eq 400 -and $r.json.code -eq 400) $r.json.message

$r = Send-Request "POST" "$baseUrl/api/orders/seata/create-idem?userId=1&amount=66" $null $token
Check "不带令牌头 400" ($r.status -eq 400 -and $r.json.code -eq 400) $r.json.message

Start-Sleep -Seconds 1
Check "两次拦截后余额不再变化" (([decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")) -eq $idemBalance0 - 66) ""
Check "两次拦截后订单不再增加" (([int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")) -eq $idemOrders0 + 1) ""

# ========== 9. 链路追踪（X-Trace-Id 响应头 + Zipkin）==========
Write-Step "9. 分布式链路追踪（X-Trace-Id 贯穿网关/order/user）"

# 用订单 2 查询，尽量避开 orderCache::users::1 缓存，保证产生 user 服务 span
$r = Send-Request "GET" "$baseUrl/api/orders/2" $null $token
$traceId = $r.headers["X-Trace-Id"]
Check "响应头带 X-Trace-Id" ($traceId -and $traceId.Length -ge 16) "X-Trace-Id=$traceId"
Check "响应体带处理实例标识（观察负载均衡用）" ($r.json.data.serverInstance) "serverInstance=$($r.json.data.serverInstance)"

Write-Host "  等待 Zipkin 上报（2 秒）..." -ForegroundColor DarkGray
Start-Sleep -Seconds 2
$spans = Invoke-RestMethod "http://localhost:9411/api/v2/trace/$traceId"
$svcNames = @($spans | ForEach-Object { $_.localEndpoint.serviceName } | Sort-Object -Unique)
Check "Zipkin 收录该 trace" ($spans.Count -ge 2) "span 数=$($spans.Count)，服务=$($svcNames -join '/')"
Check "span 覆盖 gateway 与 order" ($svcNames -contains "gateway-service" -and $svcNames -contains "order-service") ""

Write-Host "  日志串联自查（同一 traceId 应出现在两个服务日志中）：" -ForegroundColor DarkGray
Write-Host "    docker compose logs order-service --since 5m | Select-String '$traceId'" -ForegroundColor DarkGray
Write-Host "    docker compose logs user-service  --since 5m | Select-String '$traceId'" -ForegroundColor DarkGray

# ========== 10. 网关路由超时（/orders/timeout sleep 5s，网关 2s 应返回 504）==========
Write-Step "10. 网关路由超时（order 路由 metadata.response-timeout=2s）"

# 直接打 /api/orders/timeout：order-service 内部 sleep 5s，但网关 2s 后强制断开返回 504。
# 客户端实测耗时应在 2s 上下（包含网络与调度开销），远小于 5s——证明是网关层超时，不是后端自然返回。
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$r = Send-Request "GET" "$baseUrl/api/orders/timeout" $null $token
$sw.Stop()
$elapsedMs = $sw.ElapsedMilliseconds
Check "超时路由返回 504 Gateway Timeout" ($r.status -eq 504) "status=$($r.status)"
Check "客户端实测耗时 ~2s（远小于后端 5s sleep）" ($elapsedMs -lt 3000 -and $elapsedMs -ge 1500) "elapsed=${elapsedMs}ms"
Write-Host "  对比：直连 order-service（绕过网关）将走完 5s sleep 才返回 200：" -ForegroundColor DarkGray
Write-Host "    Invoke-WebRequest 'http://localhost:8082/orders/timeout' -TimeoutSec 10  # 实测约 5s" -ForegroundColor DarkGray

# ========== 11. 网关自动重试（Retry：仅 GET + 5xx，retries=2，指数退避）==========
Write-Step "11. 网关自动重试（GET 瞬时 503，首次+2 次重试共 3 次尝试）"

# 重置两个演示计数器（双副本共享，必须用 Redis key 而非实例内变量）
docker exec redis redis-cli DEL retry:demo:counter retry:demo:always-fail | Out-Null

# 场景 A：后端第 1、2 次返回 503，第 3 次 200 —— 客户端只发一次请求，网关内部重试消化前两次
$r = Send-Request "GET" "$baseUrl/api/orders/retry-demo" $null $token
Check "瞬时故障重试后最终 200（调用方无感知）" ($r.status -eq 200) "status=$($r.status)"
Check "后端实际被调用 3 次（首次 + 2 次重试）" ($r.json.data.attempts -eq 3) "attempts=$($r.json.data.attempts)"

# 场景 B：后端永远 503 —— 重试次数耗尽后，网关把最后一次 503 透传给客户端（不无限重试）
$r2 = Send-Request "GET" "$baseUrl/api/orders/retry-demo?alwaysFail=true" $null $token
Check "重试耗尽后透传最后一次 503" ($r2.status -eq 503) "status=$($r2.status)"
$backendCalls = (docker exec redis redis-cli GET retry:demo:always-fail).Trim()
Check "耗尽模式后端仅被调用 3 次（重试有上限，不放大流量）" ($backendCalls -eq "3") "backendCalls=$backendCalls"
Write-Host "  安全边界：POST 下单不重试（methods=GET），响应超时不重试（exceptions 不含 TimeoutException）" -ForegroundColor DarkGray
Write-Host "  观察后端日志可见同一次请求的 3 次尝试（可能分布在两个副本上）：" -ForegroundColor DarkGray
Write-Host "    docker compose logs order-service --since 3m | Select-String '重试演示接口'" -ForegroundColor DarkGray

# ========== 12. Sentinel 异常比例熔断（/orders/fail 连续 500 → 熔断 → 半开探测）==========
Write-Step "12. Sentinel 异常比例熔断（closed→open→half-open）"

# 容器内直连 8082 而非经网关，两个原因：
# 1) 熔断规则要求 1s 统计窗口内 ≥minRequestAmount=5 次请求，经网关 ~100ms/次会分散到
#    多个窗口凑不满样本；容器内 curl ~10ms/次，一次 exec 内循环 10 连发稳落同一窗口。
# 2) 双副本的 Sentinel 统计相互独立，直连固定容器保证样本不分散。
# 规则：Nacos SENTINEL_GROUP/order-service-degrade-rules.json
#   resource=/orders/fail, 异常比例≥0.5, minRequestAmount=5, statIntervalMs=1000, 熔断时长 10s
# 全局异常处理器会把异常就地消化成 500（Sentinel 拦截器看不见），
# controller 已用 Tracer.trace(ex) 显式上报异常比例（官方推荐做法）。
# 注：熔断打开会持续 10s，单跑本节失败重试时先等 11s 让状态机归零。

# 阶段 A（清场）：接口持续失败 → half-open 探测必失败 → 熔断反复打开，任意时刻来请求
# 大概率撞在 open 期（429）。先循环等一次"open 过期→半开放行"的真实执行（500），
# 它失败后会立刻重新打开熔断——这个时点就是后面断言的确定起点。
$warm = 0
for ($i = 0; $i -lt 3; $i++) {
    $warm = [int](docker exec spring-cloud-study-order-service-1 sh -c "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/orders/fail")
    if ($warm -eq 500) { break }
    Start-Sleep -Seconds 11   # 熔断时长 10s，等 11s 必过半开
}
Check "清场：请求真实到达后端并失败（500）" ($warm -eq 500) "warm=$warm（429=熔断开着，冷却后重试）"

# 阶段 B：紧接 10 连发。此刻熔断刚重新打开，连发应被 429 快速失败（不再真正执行）；
# 若服务刚重启处于干净 closed，则连发自身填满统计窗口（比例 1.0≥0.5）也会转 429，
# 两种起点统一断言：出现 429 即证明熔断生效（跨窗口边界时 429 会 ±1，已容错）
$raw = (docker exec spring-cloud-study-order-service-1 sh -c "for i in 1 2 3 4 5 6 7 8 9 10; do curl -s -o /dev/null -w '%{http_code} ' http://127.0.0.1:8082/orders/fail; done; echo") -join ' '
$codes = @("$raw".Trim() -split '\s+') | ForEach-Object { [int]$_ }
$cnt500 = @($codes | Where-Object { $_ -eq 500 }).Count
$cnt429 = @($codes | Where-Object { $_ -eq 429 }).Count
Check "熔断生效：连发被 429 快速失败拦截" ($cnt429 -ge 1 -and ($cnt500 + $cnt429) -eq 10) "500x$cnt500 429x$cnt429"

# 熔断 429 的响应体是 Sentinel 拦截文案（区别于业务 500 的 Result JSON）——此时熔断仍打开
$body429 = (docker exec spring-cloud-study-order-service-1 sh -c "curl -s http://127.0.0.1:8082/orders/fail") -join ''
Check "熔断 429 响应体为 Sentinel 拦截文案" ($body429 -like "*Sentinel*") $body429

# 阶段 C：等熔断时长 10s 过后转半开——放行 1 次探测请求真实执行（接口仍故障→500），
# 失败比例继续超标 → 熔断再次打开，后续请求继续 429
Write-Host "  等待熔断冷却 10s（timeWindow=10）转半开..." -ForegroundColor DarkGray
Start-Sleep -Seconds 11
$probe = [int](docker exec spring-cloud-study-order-service-1 sh -c "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/orders/fail")
Start-Sleep -Milliseconds 200
$after = [int](docker exec spring-cloud-study-order-service-1 sh -c "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/orders/fail")
Check "半开探测放行真实执行（接口仍故障 → 500）" ($probe -eq 500) "probe=$probe"
Check "探测失败后熔断重新打开（429）" ($after -eq 429) "after=$after"

Write-Host "  状态机：closed（异常比例 1.0≥0.5）→ open 10s → half-open 放行 1 次探测 → 仍失败 → open" -ForegroundColor DarkGray
Write-Host "  对比：重试解决瞬时故障（第 11 节 503 偶发），熔断解决持续故障（本节连续 500）" -ForegroundColor DarkGray

# ========== 13. 网关限流（Redis 令牌桶：登录防暴力破解 + 业务接口限流） ==========
Write-Step "13. 网关限流（RequestRateLimiter + Redis 令牌桶）"

# --- 13.1 登录接口限流（防暴力破解：replenishRate=1, burstCapacity=3）---
# 等 3 秒让令牌桶补满（burstCapacity=3），然后快速连发 5 次
# 前 3 次应放行（返回业务响应 200/400），第 4、5 次应被网关拦截返回 429
Write-Host "  等待 10s 让登录接口令牌桶补满(burstCapacity=10)..." -ForegroundColor DarkGray
Start-Sleep -Seconds 10
$login200 = 0; $login429 = 0; $last429 = $null
for ($i = 1; $i -le 15; $i++) {
    $r = Send-Request "POST" "$baseUrl/api/login" @{ username = "张三"; password = "wrong-pass" } $null
    if ($r.status -eq 429) { $login429++; $last429 = $r.json } else { $login200++ }
}
Check "登录限流: 前10次放行, 后5次被429拦截" ($login200 -ge 10 -and $login429 -ge 1) "pass=$login200 429=$login429"
# 429 的友好响应体直接取连发中被拦的那次：单独补发会和"1 个/秒的令牌补充"竞态，
# 补发晚一拍令牌就恢复、请求穿透到业务层返回 401，断言会随机失败
Check "登录限流: 429 响应体含 code=429 + 友好 message" ($last429 -and $last429.code -eq 429 -and $last429.message -like "*频繁*") "code=$($last429.code) msg=$($last429.message)"

# --- 13.2 order 接口 Sentinel 流控（Nacos 下发规则 QPS=5，资源 /orders/{id}）---
# 容器内直连 -1 实例：双副本的 Sentinel 统计相互独立，经网关会分散样本；
# 一次 docker exec 内循环 12 连发（~10ms/次）稳落同一统计窗口。
# 命中流控的 429 由 Sentinel 拦截器直接返回，body 为 "Blocked by Sentinel (flow limiting)"。
# 网关自身的 Redis 令牌桶（order 路由 2/s）在 12 连发内不触发（桶容量 20），两层限流互不干扰。
$lines = @(docker exec spring-cloud-study-order-service-1 sh -c "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do curl -s -w '|%{http_code}' http://127.0.0.1:8082/orders/1; echo; done")
$order200 = 0; $order429 = 0; $sentinel429 = 0
foreach ($line in $lines) {
    if ($line -match '\|(\d+)\s*$') {
        $code = [int]$Matches[1]
        $body = ($line -replace '\|\d+\s*$', '')
        if ($code -eq 200) { $order200++ }
        elseif ($code -eq 429) { $order429++; if ($body -like '*Sentinel*') { $sentinel429++ } }
    }
}
Check "order Sentinel 流控: QPS=5 优先拦截（约 5 过 7 拦）" ($order200 -in 4..8 -and $order429 -ge 4) "200=$order200 429=$order429"
Check "所有 429 均为 Sentinel 拦截文案" ($order429 -eq 0 -or $sentinel429 -eq $order429) "sentinel429=$sentinel429/$order429"

# --- 13.3 user-service 限流（replenishRate=2, burstCapacity=4）---
Write-Host "  等待 10s 让 user 接口令牌桶补满(burstCapacity=20)..." -ForegroundColor DarkGray
Start-Sleep -Seconds 10
$user200 = 0; $user429 = 0
for ($i = 1; $i -le 25; $i++) {
    $r = Send-Request "GET" "$baseUrl/api/users/1" $null $token
    if ($r.status -eq 429) { $user429++ } elseif ($r.status -eq 200) { $user200++ }
}
Check "user限流: 前20次放行, 后5次被429拦截" ($user200 -ge 20 -and $user429 -ge 1) "200=$user200 429=$user429"

Write-Host "  限流维度：userKeyResolver 优先按 token(每个登录用户独立桶)，无 token 按客户端 IP" -ForegroundColor DarkGray
Write-Host "  算法：Redis + Lua 令牌桶，replenishRate=每秒补令牌数，burstCapacity=桶最大容量" -ForegroundColor DarkGray

# ========== 14. RBAC 权限控制（角色-路径匹配） ==========
Write-Step "14. RBAC 权限控制（PermissionGlobalFilter）"

# 张三(ADMIN) 访问管理接口 → 200
$r = Send-Request "GET" "$baseUrl/api/admin/users" $null $token
Check "ADMIN 访问 /api/admin/users -> 200" ($r.status -eq 200) "status=$($r.status)"

# 李四(USER) 登录 → 获取 userToken
$r2 = Send-Request "POST" "$baseUrl/api/login" @{ username = "李四"; password = "123456" } $null
$userToken = $r2.json.data.accessToken
Check "李四(USER) 登录获取 userToken" ($r2.status -eq 200 -and $userToken) "status=$($r2.status)"

# 李四(USER) 访问管理接口 → 403
$r3 = Send-Request "GET" "$baseUrl/api/admin/users" $null $userToken
Check "USER 访问 /api/admin/users -> 403" ($r3.status -eq 403 -and $r3.json.code -eq 403) "status=$($r3.status) code=$($r3.json.code)"

Write-Host "  RBAC: 网关 PermissionGlobalFilter 按 auth.permission-rules 做路径-角色匹配" -ForegroundColor DarkGray
Write-Host "  规则: /api/admin/** -> [ADMIN]，未匹配规则的路径默认放行" -ForegroundColor DarkGray

# ========== 15. Token 刷新机制（access + refresh 双令牌） ==========
Write-Step "15. Token 刷新机制（双令牌）"

# 用 refreshToken 刷新 → 返回新 accessToken
$r = Send-Request "POST" "$baseUrl/api/refresh" @{ refreshToken = $refreshToken } $null
Check "refresh token 换新 access token -> 200" ($r.status -eq 200 -and $r.json.data.accessToken) "status=$($r.status)"
$newAccessToken = $r.json.data.accessToken

# 用新 accessToken 访问接口 → 200
$r = Send-Request "GET" "$baseUrl/api/orders/1" $null $newAccessToken
Check "新 access token 访问接口 -> 200" ($r.status -eq 200) "status=$($r.status)"

Write-Host "  双令牌: access(30min)用于业务鉴权, refresh(7d)用于刷新access" -ForegroundColor DarkGray
Write-Host "  refresh token 存 Redis(key=refresh:{userId}), 刷新时校验值匹配" -ForegroundColor DarkGray

Start-Sleep -Seconds 3
# ========== 16. 登出 + Redis 黑名单（主动吊销） ==========
Write-Step "16. 登出 + Redis 黑名单"

# 用张三的 token 登出 → 200
$r = Send-Request "POST" "$baseUrl/api/logout" $null $token
Check "登出 -> 200" ($r.status -eq 200) "status=$($r.status)"

# 旧 token 访问接口 → 401（在黑名单中）
$r = Send-Request "GET" "$baseUrl/api/orders/1" $null $token
Check "登出后旧 token -> 401" ($r.status -eq 401 -and $r.json.code -eq 401) "status=$($r.status) code=$($r.json.code)"

# 用旧 refreshToken 刷新 → 401（refresh 已删除）
$r = Send-Request "POST" "$baseUrl/api/refresh" @{ refreshToken = $refreshToken } $null
Check "登出后旧 refreshToken -> 401" ($r.status -eq 401) "status=$($r.status)"

Write-Host "  黑名单: 登出时 Redis SET blacklist:{token} 1 EX {剩余秒数}" -ForegroundColor DarkGray
Write-Host "  网关验签后检查黑名单, 在黑名单则 401; 同时删除 refresh token" -ForegroundColor DarkGray
# ========== 汇总 ==========
Write-Host "`n========================================" -ForegroundColor Yellow
if ($fail -eq 0) {
    Write-Host "  全部 $pass 项检查通过 ✅" -ForegroundColor Green
}
else {
    Write-Host "  PASS=$pass  FAIL=$fail ❌（看 docker logs user-service / order-service 排查）" -ForegroundColor Red
}
Write-Host "========================================" -ForegroundColor Yellow
Write-Host "  数据检查点："
Write-Host '    docker exec mysql mysql -uroot -p123456 -e "SELECT id,balance,points FROM study_cloud.t_user WHERE id=1;"'
Write-Host '    docker exec mysql mysql -uroot -p123456 -e "SELECT * FROM study_cloud.t_points_log ORDER BY id DESC LIMIT 5;"'
Write-Host '    docker exec redis redis-cli keys "*"'
Write-Host '    docker logs order-service --tail 100'
exit $fail
Write-Host '    docker exec redis redis-cli keys "*"'
Write-Host '    docker logs order-service --tail 100'
exit $fail
