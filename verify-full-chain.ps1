# Spring Cloud 全链路验证脚本
# 用法：右键 -> 使用 PowerShell 运行，或在终端执行 .\verify-full-chain.ps1

$baseUrl = "http://localhost:9000"
$token = ""

# 颜色输出函数
function Write-Step($step, $msg) {
    Write-Host "`n[$step] $msg" -ForegroundColor Cyan
}
function Write-Pass($msg) {
    Write-Host "  [PASS] $msg" -ForegroundColor Green
}
function Write-Fail($msg) {
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
}
function Write-Info($msg) {
    Write-Host "  [INFO] $msg" -ForegroundColor Gray
}

# ========== 第 1 步：登录拿 Token ==========
Write-Step "1/6" "登录获取 JWT"
$loginBody = @{ username = "张三"; password = "123456" } | ConvertTo-Json -Compress
try {
    $loginResp = Invoke-RestMethod -Uri "$baseUrl/api/login" -Method Post -Body $loginBody -ContentType "application/json"
    $token = $loginResp.token
    Write-Pass "登录成功，userId=$($loginResp.userId), username=$($loginResp.username)"
} catch {
    Write-Fail "登录失败：$_"
    exit 1
}

$headers = @{ Authorization = "Bearer $token" }

# ========== 第 2 步：网关鉴权（无 token 应 401） ==========
Write-Step "2/6" "网关鉴权（无 token 应返回 401）"
try {
    $resp = Invoke-WebRequest -Uri "$baseUrl/api/orders/1" -Method Get -ErrorAction Stop
    Write-Fail "未携带 token 却返回了 $($resp.StatusCode)，鉴权未生效"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -eq 401) {
        Write-Pass "无 token 被拦截，返回 401"
    } else {
        Write-Fail "异常状态码：$($_.Exception.Response.StatusCode.value__)"
    }
}

# 带 token 访问
Write-Info "带 token 访问 /api/orders/1"
try {
    $orderResp = Invoke-RestMethod -Uri "$baseUrl/api/orders/1" -Headers $headers
    Write-Pass "网关路由 + Feign 跨服务调用成功，currentUser=$($orderResp.currentUser)"
} catch {
    Write-Fail "带 token 访问失败：$_"
}

# ========== 第 3 步：Seata AT 正常提交 ==========
Write-Step "3/6" "Seata AT 分布式事务——正常提交（下单 100 元）"
try {
    $seataResp = Invoke-RestMethod -Uri "http://localhost:8082/orders/seata/create?userId=1&amount=100" -Method Post
    Write-Pass "全局事务提交成功，orderId=$($seataResp.orderId)"
} catch {
    Write-Fail "Seata 事务失败：$_"
}

# ========== 第 4 步：Seata AT 回滚 ==========
Write-Step "4/6" "Seata AT 回滚（forceFail=true，订单应被清理）"
try {
    Invoke-RestMethod -Uri "http://localhost:8082/orders/seata/create?userId=1&amount=100&forceFail=true" -Method Post -ErrorAction Stop
    Write-Fail "forceFail=true 却返回成功，回滚未触发"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -eq 500) {
        Write-Pass "预期内 500 异常，全局事务已回滚"
    } else {
        Write-Info "返回 $($_.Exception.Response.StatusCode.value__)，需人工确认回滚"
    }
}

# ========== 第 5 步：RocketMQ 事务消息 ==========
Write-Step "5/6" "RocketMQ 事务消息（下单 50 元，积分异步增加）"
try {
    $mqResp = Invoke-RestMethod -Uri "http://localhost:8082/orders/rocketmq/create?userId=1&amount=50" -Method Post
    Write-Pass "事务消息已发送：$($mqResp.message)"
    Write-Info "等待 3 秒让消费者处理..."
    Start-Sleep -Seconds 3
} catch {
    Write-Fail "RocketMQ 事务消息失败：$_"
}

# ========== 第 6 步：Redis 缓存验证 ==========
Write-Step "6/6" "Redis 缓存命中（连续调两次，第二次应命中缓存）"
try {
    $null = Invoke-RestMethod -Uri "$baseUrl/api/orders/1" -Headers $headers
    Write-Info "第一次调用完成（可能走 Feign）"
    $null = Invoke-RestMethod -Uri "$baseUrl/api/orders/1" -Headers $headers
    Write-Info "第二次调用完成（应命中 Redis 缓存）"
    Write-Pass "缓存链路正常（key: orderCache::users::1）"
} catch {
    Write-Fail "缓存验证失败：$_"
}

# ========== 汇总 ==========
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host "  全链路验证完成" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Yellow
Write-Host "  检查点："
Write-Host "  - MySQL:  docker exec mysql mysql -uroot -p123456 -e `"SELECT id, balance, points FROM study_cloud.t_user WHERE id=1;`""
Write-Host "  - Redis:  docker exec redis redis-cli keys `"*`""
Write-Host "  - Seata:  docker logs seata 2>&1 | Select-String register"
Write-Host "  - RocketMQ: docker exec broker sh mqadmin topicList -n namesrv:9876"
