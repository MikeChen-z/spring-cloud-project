# ============================================================
# CAS 竞态测试：pay 与 admin-cancel 并发，验证不会"支付成功却被取消"
# 背景：下单后发送的延迟消息到期时会取消未支付订单，与用户支付存在竞态；
#       payOrder/cancelOrder 用 WHERE status=0 条件更新(CAS)消除丢失更新。
# 用法：在项目根目录执行
#       pwsh -File .\scripts\cas-race-test.ps1 [-Rounds 30]
# 前置：docker compose up -d，网关 9000 可访问（脚本自带登录与 429 退避重试）
# ============================================================
param(
    [int]$Rounds = 30,
    [string]$Base = "http://localhost:9000",
    [string]$Username = "张三",
    [string]$Password = "123456"
)

# 并发执行体：在独立 runspace 线程里发 POST，返回状态码和响应体
$worker = {
    param($Url, $Token)
    try {
        $req = [System.Net.HttpWebRequest]::Create($Url)
        $req.Method = "POST"
        $req.ContentType = "application/json"
        $req.Timeout = 15000
        $req.Headers.Add("Authorization", "Bearer $Token")
        $req.ContentLength = 0
        $s = $req.GetRequestStream(); $s.Close()
        $resp = $req.GetResponse()
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
        return @{ code = [int]$resp.StatusCode; body = $sr.ReadToEnd() }
    }
    catch [System.Net.WebException] {
        $r = $_.Exception.Response
        if ($r) {
            $sr = New-Object System.IO.StreamReader($r.GetResponseStream())
            return @{ code = [int]$r.StatusCode; body = $sr.ReadToEnd() }
        }
        return @{ code = 0; body = $_.Exception.Message }
    }
}

# 带 429 重试的请求（网关令牌桶 2 QPS，被限流就退避重试）
function Invoke-Api($Method, $Url, $Token) {
    for ($i = 0; $i -lt 8; $i++) {
        try {
            $resp = Invoke-RestMethod -Uri $Url -Method $Method -TimeoutSec 15 `
                -Headers @{ Authorization = "Bearer $Token" } -ContentType "application/json"
            return @{ ok = $true; data = $resp }
        }
        catch {
            $code = 0
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            if ($code -eq 429) { Start-Sleep -Milliseconds 700; continue }
            return @{ ok = $false; code = $code; err = $_.Exception.Message }
        }
    }
    return @{ ok = $false; code = 429; err = "重试耗尽" }
}

Write-Host "== CAS 支付/取消竞态测试，共 $Rounds 轮 ==" -ForegroundColor Cyan

# 1. 登录
$loginBody = "{`"username`":`"$Username`",`"password`":`"$Password`"}"
$login = Invoke-RestMethod -Uri "$Base/api/login" -Method Post -ContentType "application/json" -Body $loginBody -TimeoutSec 15
$token = $login.data.accessToken
Write-Host "登录成功" -ForegroundColor Green

$results = @()
$bugCount = 0

for ($i = 1; $i -le $Rounds; $i++) {
    # 2. 下单（amount=1）
    $create = Invoke-Api "POST" "$Base/api/orders/seata/create?userId=1&amount=1" $token
    if (-not $create.ok) {
        Write-Host ("[{0,2}] 下单失败 code={1} {2}" -f $i, $create.code, $create.err) -ForegroundColor Yellow
        Start-Sleep -Milliseconds 800
        continue
    }
    $orderId = $create.data.data.orderId

    # 3. 同一时刻并发发起 pay 和 admin-cancel（两个 runspace 线程，背靠背 BeginInvoke）
    $ps1 = [PowerShell]::Create().AddScript($worker).AddArgument("$Base/api/orders/$orderId/pay").AddArgument($token)
    $ps2 = [PowerShell]::Create().AddScript($worker).AddArgument("$Base/api/orders/$orderId/admin-cancel").AddArgument($token)
    $h1 = $ps1.BeginInvoke()
    $h2 = $ps2.BeginInvoke()
    $pay = $ps1.EndInvoke($h1) | Select-Object -First 1
    $cancel = $ps2.EndInvoke($h2) | Select-Object -First 1
    $ps1.Dispose(); $ps2.Dispose()

    # 4. 查最终状态（429 重试）
    Start-Sleep -Milliseconds 100
    $st = Invoke-Api "GET" "$Base/api/orders/$orderId/status" $token
    $final = if ($st.ok) { $st.data.data.status } else { -1 }

    # 5. 判定
    $verdict = "?"
    if ($pay.code -eq 200 -and $final -eq 1) { $verdict = "PAY_WON" }
    elseif ($cancel.code -eq 200 -and $final -eq 2) { $verdict = "CANCEL_WON" }
    elseif ($pay.code -eq 200 -and $final -eq 2) { $verdict = "BUG:支付成功却被取消"; $bugCount++ }
    elseif ($cancel.code -eq 200 -and $final -eq 1) { $verdict = "BUG:取消成功却已支付"; $bugCount++ }
    else { $verdict = "OTHER(pay=$($pay.code),cancel=$($cancel.code),final=$final)" }

    $color = if ($verdict -like "BUG*") { "Red" } elseif ($verdict -eq "OTHER*") { "Yellow" } else { "Gray" }
    Write-Host ("[{0,2}] orderId={1} pay={2} cancel={3} final={4} => {5}" -f `
            $i, $orderId, $pay.code, $cancel.code, $final, $verdict) -ForegroundColor $color

    $results += [PSCustomObject]@{
        Round = $i; OrderId = $orderId
        PayCode = $pay.code; CancelCode = $cancel.code; FinalStatus = $final; Verdict = $verdict
    }
    Start-Sleep -Milliseconds 900   # 避让网关 2 QPS 限流
}

Write-Host ""
Write-Host "== 汇总 ==" -ForegroundColor Cyan
$results | Group-Object Verdict | ForEach-Object {
    $c = if ($_.Name -like "BUG*") { "Red" } else { "Green" }
    Write-Host ("{0} : {1}" -f $_.Name, $_.Count) -ForegroundColor $c
}
Write-Host ""
if ($bugCount -eq 0) {
    Write-Host "结论：0 个竞态矛盾，CAS 条件更新有效。" -ForegroundColor Green
}
else {
    Write-Host "结论：发现 $bugCount 个丢失更新！CAS 未生效，需排查。" -ForegroundColor Red
}