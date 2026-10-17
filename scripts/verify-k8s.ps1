#requires -Version 5
# 第 24 章：微服务上 K8s 后的全链路验证脚本（Windows PowerShell 5.1 / pwsh 7 均可运行）
# 用法：powershell -File scripts/verify-k8s.ps1
# 出口码：0 = 全部通过；1 = 有失败项
$ErrorActionPreference = 'Continue'  # 逐条 Check 自行判分，不因 kubectl stderr 中断

function Write-Step($msg) { Write-Host "`n========== $msg ==========" -ForegroundColor Cyan }

$script:pass = 0
$script:fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++; Write-Host "  [PASS] $name  $detail" -ForegroundColor Green }
    else     { $script:fail++; Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red }
}

function Send-Request($Method, $Url, $Body, $Token) {
    # 返回带 StatusCode/Content 的对象；非 2xx 也算"有响应"（PS5.1 无 -SkipHttpErrorCheck，用 catch 合成）
    $req = @{ Method = $Method; Uri = $Url; UseBasicParsing = $true; TimeoutSec = 20 }
    if ($Body) { $req.ContentType = 'application/json; charset=utf-8'; $req.Body = ($Body | ConvertTo-Json) }
    if ($Token) { $req.Headers = @{ Authorization = "Bearer $Token" } }
    try { return Invoke-WebRequest @req }
    catch {
        if ($_.Exception.Response) {
            $rs = $_.Exception.Response
            $sr = New-Object System.IO.StreamReader($rs.GetResponseStream())
            return [pscustomobject]@{ StatusCode = [int]$rs.StatusCode; Content = $sr.ReadToEnd() }
        }
        return [pscustomobject]@{ StatusCode = 0; Content = $_.Exception.Message }
    }
}

function Get-Token {
    $r = Send-Request 'POST' "$baseUrl/api/login" @{ username = '张三'; password = '123456' } $null
    return ($r.Content | ConvertFrom-Json).data.accessToken
}

$baseUrl = 'http://localhost:9000'
$promUrl = 'http://localhost:9090'

# ---------- 1. K8s Pod 就绪 ----------
Write-Step '1. K8s Pod 就绪检查'
$deploys = @('user-service', 'order-service', 'gateway-service', 'prometheus')
foreach ($d in $deploys) {
    $line = kubectl get pods -n study -l app=$d --no-headers 2>$null | Select-Object -First 1
    $ready = ($line -match '1/1\s+Running')
    Check "Deployment $d" $ready $(if ($line) { $line.Trim() } else { '无 Pod' })
}

# ---------- 2. compose 侧基线 ----------
Write-Step '2. LB 端口与 compose 侧基线'
$nacosOnline = docker exec nacos curl -s -o /dev/null -w '%{http_code}' http://localhost:8848/nacos/ 2>$null
Check 'compose nacos 在线' ($nacosOnline -eq '200') "status=$nacosOnline"
$composeProm = docker ps --format '{{.Names}}' 2>$null | Select-String -Pattern '^prometheus$'
Check 'compose prometheus 已停用' ($null -eq $composeProm) '（避免与 K8s prometheus 抢宿主 9090）'

# ---------- 3. 登录 + 下单全链路 ----------
Write-Step '3. 登录 + 下单全链路'
$token = Get-Token
Check '登录（张三/123456）' ($token -and $token.Length -gt 10) "token=$($token.Substring(0,[Math]::Min(12,$token.Length)))..."

# 余额/订单数没有对应 HTTP 查询端点，与 verify-full-chain.ps1 一样直查 compose 里的 mysql
function Get-DbValue($sql) {
    return ((docker exec mysql mysql -uroot -p123456 -N -B -e $sql 2>$null | Select-Object -First 1).Trim())
}

# Seata 链路预热（防长时间空闲后首条全局事务冷启动超过网关 2s 超时 -> 504），
# 随即重置基线，预热单不污染差值断言（与 verify-full-chain.ps1 第 0 节同法）
$null = Send-Request 'POST' "$baseUrl/api/orders/seata/create?userId=1&amount=1" $null $token
Start-Sleep -Seconds 2   # 等预热单的积分消息消费完再重置
$null = docker exec mysql mysql -uroot -p123456 -e "UPDATE study_cloud.t_user SET balance=1000, points=1000 WHERE id=1;" 2>$null
$balance0 = [decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")
$orders0 = [int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")

$r2 = Send-Request 'POST' "$baseUrl/api/orders/seata/create?userId=1&amount=100" $null $token
Check 'Seata 下单 100 元' ($r2.StatusCode -eq 200) "status=$($r2.StatusCode)"
Start-Sleep -Seconds 3   # 等积分消息消费

$balance1 = [decimal](Get-DbValue "SELECT balance FROM study_cloud.t_user WHERE id=1")
$orders1 = [int](Get-DbValue "SELECT COUNT(*) FROM order_db.t_order")
Check '余额 -100' (($balance0 - $balance1) -eq 100) "$balance0 -> $balance1"
Check '订单 +1' (($orders1 - $orders0) -eq 1) "$orders0 -> $orders1"

$u1 = Send-Request 'GET' "$baseUrl/api/users/1" $null $token
$points1 = [int](($u1.Content | ConvertFrom-Json).data.points)
Check '积分 +100（MQ 消费正常）' ($points1 -eq 1100) "points=$points1（MQ 消费有秒级延迟，首轮失败可重跑脚本）"

# order 侧没有订单列表接口；GET /orders/{id} 内部经 Feign 调 user-service，可验证跨服务链路
$r3 = Send-Request 'GET' "$baseUrl/api/orders/1" $null $token
$feignUser = ($r3.Content | ConvertFrom-Json).data.user
Check '订单查询（跨服务 Feign）' ($r3.StatusCode -eq 200 -and $feignUser) "status=$($r3.StatusCode)"

$r4 = Send-Request 'GET' "$baseUrl/api/users/1" $null $token
Check '用户查询（缓存回源路径）' ($r4.StatusCode -eq 200) "status=$($r4.StatusCode)"

# ---------- 4. Prometheus K8s SD 抓取 ----------
Write-Step '4. Prometheus K8s SD 抓取'
try {
    $targets = (Invoke-RestMethod "$promUrl/api/v1/targets?state=active" -TimeoutSec 10).data.activeTargets
    foreach ($j in @('user-service', 'order-service', 'gateway-service')) {
        $t = $targets | Where-Object { $_.labels.job -eq $j } | Select-Object -First 1
        Check "target $j up" ($t -and $t.health -eq 'up') $(if ($t) { "$($t.scrapeUrl) health=$($t.health)" } else { '未发现目标' })
    }
} catch { Check 'targets API' $false $_.Exception.Message }

# ---------- 5. 告警状态 ----------
Write-Step '5. 告警状态'
try {
    $alerts = (Invoke-RestMethod "$promUrl/api/v1/alerts" -TimeoutSec 10).data.alerts
    $firing = @($alerts | Where-Object { $_.state -eq 'firing' })
    Check '无 firing 告警' ($firing.Count -eq 0) $(if ($firing.Count) { ($firing | ForEach-Object { $_.labels.alertname }) -join ',' } else { '' })
} catch { Check 'alerts API' $false $_.Exception.Message }

Write-Host "`nPASS=$script:pass  FAIL=$script:fail" -ForegroundColor $(if ($script:fail) { 'Red' } else { 'Green' })
exit $(if ($script:fail) { 1 } else { 0 })