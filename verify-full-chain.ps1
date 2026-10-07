# Spring Cloud 全链路验证脚本（统一返回体 Result 契约版）
# 用法：在项目根目录执行  .\verify-full-chain.ps1
# 前置：docker compose up -d --scale user-service=2 --scale order-service=2，
#       全部容器就绪（约 1 分钟，双副本共 12 个容器）
#
# 覆盖：登录 / 参数校验(@Valid+@Validated) / 网关鉴权 /
#       Seata AT 提交与回滚 / RocketMQ 普通消息+事务消息 / Redis 缓存 /
#       接口幂等(Redis 防重令牌) / 链路追踪(X-Trace-Id + Zipkin)
# 断言全部基于"请求前后数据库差值"；脚本开头会把张三账户重置为余额/积分各 1000，可无限次重复执行

# --- 控制台 UTF-8，保证中文响应不乱码（PowerShell 5.1 必需）---
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$baseUrl = "http://localhost:9000"
$pass = 0; $fail = 0

function Write-Step($msg) { Write-Host "`n========== $msg ==========" -ForegroundColor Cyan }
function Check($name, $cond, $detail) {
    if ($cond) { Write-Host "  [PASS] $name  $detail" -ForegroundColor Green; $script:pass++ }
    else { Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red; $script:fail++ }
}

# 发 HTTP 请求，统一返回 @{status; json; headers}；4xx/5xx 不抛异常，交给断言判断
# $extraHeaders：额外请求头（如幂等接口的 Idempotent-Token）
# $bodyObj 传字符串时原样作为 JSON 体发送（用于构造坏 JSON 验证 400）
function Send-Request($method, $url, $bodyObj, $token, $extraHeaders) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($extraHeaders) { foreach ($k in $extraHeaders.Keys) { $headers[$k] = $extraHeaders[$k] } }
    try {
        $p = @{ Uri = $url; Method = $method; Headers = $headers; UseBasicParsing = $true; ErrorAction = "Stop" }
        if ($null -ne $bodyObj) {
            # PS 5.1 的 Invoke-WebRequest 默认按非 UTF-8 编码 body，中文用户名会乱码，必须显式转字节
            $raw = if ($bodyObj -is [string]) { $bodyObj } else { $bodyObj | ConvertTo-Json -Compress }
            $p.Body = [System.Text.Encoding]::UTF8.GetBytes($raw)
            $p.ContentType = "application/json; charset=utf-8"
        }
        $resp = Invoke-WebRequest @p
        return @{ status = [int]$resp.StatusCode; json = ($resp.Content | ConvertFrom-Json); headers = $resp.Headers }
    }
    catch {
        $r = $_.Exception.Response
        $json = $null
        try {
            $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
            $json = $reader.ReadToEnd() | ConvertFrom-Json
        }
        catch {}
        return @{ status = [int]$r.StatusCode; json = $json; headers = $r.Headers }
    }
}

# 查 MySQL 单值（-N 去表头 -B tab 分隔）
function Get-DbValue($sql) {
    return ((docker exec mysql mysql -uroot -p123456 -N -B -e $sql 2>$null | Select-Object -First 1).Trim())
}

# ========== 基线数据 ==========
Write-Step "0. 读取基线数据"
# 每轮脚本净扣减 100+50+66=216 元，反复执行会因"余额不足"误失败；
# 开跑前把测试账户重置到固定基线（断言全部基于本轮差值，重置不影响结论）
$null = docker exec mysql mysql -uroot -p123456 -e "UPDATE study_cloud.t_user SET balance=1000, points=1000 WHERE id=1;" 2>$null
$balance0 = [decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")
$points0 = [decimal](Get-DbValue "SELECT points FROM study_cloud.t_user WHERE id=1")
$orders0 = [int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")
Write-Host "  余额=$balance0  积分=$points0  订单数=$orders0"

# ========== 1. 登录与参数校验 ==========
Write-Step "1. 登录与参数校验"

$r = Send-Request "POST" "$baseUrl/api/login" @{ username = "张三"; password = "123456" } $null
Check "正常登录 200" ($r.status -eq 200 -and $r.json.data.token) "code=$($r.json.code)"
$token = $r.json.data.token

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
$spans = $null
try { $spans = Invoke-RestMethod "http://localhost:9411/api/v2/trace/$traceId" -TimeoutSec 5 } catch {}
$svcNames = @($spans | ForEach-Object { $_.localEndpoint.serviceName } | Sort-Object -Unique)
Check "Zipkin 收录该 trace" ($spans.Count -ge 2) "span 数=$($spans.Count)，服务=$($svcNames -join '/')"
Check "span 覆盖 gateway 与 order" ($svcNames -contains "gateway-service" -and $svcNames -contains "order-service") ""

Write-Host "  日志串联自查（同一 traceId 应出现在两个服务日志中）：" -ForegroundColor DarkGray
Write-Host "    docker compose logs order-service --since 5m | Select-String '$traceId'" -ForegroundColor DarkGray
Write-Host "    docker compose logs user-service  --since 5m | Select-String '$traceId'" -ForegroundColor DarkGray

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
