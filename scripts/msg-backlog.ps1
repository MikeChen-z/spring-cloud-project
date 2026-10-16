# ============================================================
# 消息积压验证：瞬时洪峰（sendOneWay）vs 慢消费，观察 Diff Total 冲高→回落
# 用法：在项目根目录执行
#       pwsh -File .\scripts\msg-backlog.ps1 [-Count 3000] [-SleepMs 100]
# 前置：docker compose up -d，网关 9000 可访问（脚本自带登录）
# 运行环境：PowerShell 7 (pwsh)——默认全链路 UTF-8
#
# 原理：
#   生产端 sendOneWay 一次性灌入 N 条（单向发送不等 Broker ACK，生产速率极高）；
#   消费端每条 sleep SleepMs 毫秒，单实例消费上限 ≈ consumeThreadNumber ÷ (SleepMs/1000)。
#   瞬时生产速率 >> 消费速率 ⇒ Broker 端 Diff Total(=BrokerOffset-ConsumerOffset) 暴涨；
#   随后以稳定速率回落，回到起始水位即"追平"（也证明 N 条全部消费、无丢失）。
#
# 关键坑（见 order-service BacklogConsumer）：真正决定并发的是 consumeThreadNumber
#  （映射为 consumeThreadMin），consumeThreadMax 是摆设——消费线程池用无界队列，
#  队列永不填满，maxPoolSize 永不扩容，实际并发 = corePoolSize。
# 追平手段：① 调大 consumeThreadNumber ② 多实例消费（--scale order-service=N）
#
# 实测参考（Count=6000, SleepMs=100）：20 线程约 29.8s、128 线程约 4.3s、双实例约 14.9s
# ============================================================
param(
    [int]$Count = 3000,
    [int]$SleepMs = 100,
    [string]$Base = "http://localhost:9000",
    [string]$Username = "张三",
    [string]$Password = "123456",
    [string]$Group = "order-backlog-consumer-group",
    [int]$DrainTimeoutSec = 120
)

$ErrorActionPreference = "Stop"

# 读消费组积压：broker 侧 mqadmin consumerProgress，解析 Diff Total 与 Consume TPS
function Get-Backlog {
    $raw = (docker exec broker sh mqadmin consumerProgress -g $Group -n namesrv:9876 2>&1) -join "`n"
    $diff = 0; $tps = 0.0
    if ($raw -match 'Diff Total:\s*(\d+)') { $diff = [int]$Matches[1] }
    if ($raw -match 'Consume TPS:\s*([\d.]+)') { $tps = [double]$Matches[1] }
    return @{ diff = $diff; tps = $tps }
}

Write-Host "== 消息积压验证：灌入 $Count 条，消费端每条 sleep ${SleepMs}ms ==" -ForegroundColor Cyan

# 1. 登录
$login = Invoke-RestMethod -Uri "$Base/api/login" -Method Post -ContentType "application/json" `
    -Body "{`"username`":`"$Username`",`"password`":`"$Password`"}"
$token = $login.data.accessToken
$h = @{ Authorization = "Bearer $token" }
Write-Host "登录成功" -ForegroundColor Green

# 2. 消费实例数（决定消费总能力：每实例各持 consumeThreadNumber 个线程）
$instances = @(docker ps --filter "name=order-service" --format "{{.Names}}").Count
if ($instances -lt 1) { $instances = 1 }
$threadPerInstance = 20                                 # BacklogConsumer.consumeThreadNumber
$theory = [int]($threadPerInstance * $instances / ($SleepMs / 1000.0))

# 3. 记录起始水位（上一轮若已清空则为 0）
$before = Get-Backlog
Write-Host ("起始积压 Diff Total = {0}，消费实例 = {1}，消费上限约 {2} msg/s" -f $before.diff, $instances, $theory)

# 4. 瞬时灌入（sendOneWay 洪峰）——经网关，注意 order 路由 2s 响应超时
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$res = Invoke-RestMethod -Uri "$Base/api/orders/backlog-demo?count=$Count&sleepMs=$SleepMs" -Method Post -Headers $h -SkipHttpErrorCheck
$sw.Stop()
$produceMs = $sw.ElapsedMilliseconds
$sendCostMs = if ($null -ne $res.data -and $null -ne $res.data.sendCostMs) { [int]$res.data.sendCostMs } else { $produceMs }
if ($null -eq $res.data) {
    Write-Host "  灌入接口未正常返回 body（可能网关 2s 超时），消息仍可能已发出，继续观察积压" -ForegroundColor Yellow
}
$produceRate = [int]($Count * 1000L / [Math]::Max($sendCostMs, 1))
Write-Host ("  灌入完成：服务端生产 {0}ms，生产速率约 {1} msg/s" -f $sendCostMs, $produceRate) -ForegroundColor Yellow

# 5. 轮询积压：先立即读一次抓峰值，再每 500ms 采样，直到回到起始水位
#    注意：每次 mqadmin 都要起一个 JVM（约 3.5s），实际采样间隔会被撑到 ~4s，
#    因此追平耗时是 4s 粒度；但生产是瞬时的、消费是慢的，抓峰值不受影响。
$peak = $before.diff; $peakTps = 0.0
$samples = @()
$drainSw = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = (Get-Date).AddSeconds($DrainTimeoutSec)
while ((Get-Date) -lt $deadline) {
    $b = Get-Backlog
    if ($b.diff -gt $peak) { $peak = $b.diff }
    if ($b.tps -gt $peakTps) { $peakTps = $b.tps }
    $samples += $b.diff
    Write-Host ("  t={0,5}ms  Diff={1,6}  ConsumeTPS={2}" -f $drainSw.ElapsedMilliseconds, $b.diff, $b.tps) -ForegroundColor DarkGray
    if ($b.diff -le $before.diff) { break }
    Start-Sleep -Milliseconds 500
}
$drainSw.Stop()
$drainSec = [Math]::Round($drainSw.Elapsed.TotalSeconds, 2)
$consumedDelta = $peak - $before.diff
$avgRate = if ($drainSec -gt 0) { [int]($consumedDelta / $drainSec) } else { 0 }

# 6. 汇总与断言
$script:pass = 0; $script:fail = 0
function Check($name, $cond, $detail) {
    if ($cond) { Write-Host "  [PASS] $name  $detail" -ForegroundColor Green; $script:pass++ }
    else { Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red; $script:fail++ }
}

Write-Host "`n== 结果 ==" -ForegroundColor Cyan
Write-Host ("  瞬时洪峰：生产约 {0} msg/s  vs  消费上限约 {1} msg/s（{2} 实例 x {3} 线程 ÷ {4}ms）" -f `
        $produceRate, $theory, $instances, $threadPerInstance, $SleepMs)
Write-Host ("  峰值积压 Diff = {0}（超出起始 {1} +{2}）" -f $peak, $before.diff, $consumedDelta)
Write-Host ("  追平耗时 {0}s，平均消费速率约 {1} msg/s，峰值 ConsumeTPS={2}" -f $drainSec, $avgRate, $peakTps)

Check "瞬时洪峰全部转为积压（峰值增量≈灌入量）" ($consumedDelta -ge [int]($Count * 0.9)) "增量=$consumedDelta 灌入=$Count"
Check "生产速率远高于消费速率（积压成因）" ($produceRate -gt $theory) "produce=$produceRate 消费上限≈$theory"
Check "积压被追平回到起始水位（N 条全消费，无丢失）" ($samples[-1] -le $before.diff) "末次 Diff=$($samples[-1])"
Check "平均消费速率量级合理（≈线程数÷单条耗时）" ($avgRate -ge [int]($theory * 0.5) -and $avgRate -le [int]($theory * 1.5)) "avg=$avgRate 理论≈$theory"

Write-Host ""
Write-Host "  结论：生产端 sendOneWay 使 BrokerOffset 暴涨，ConsumerOffset 缓慢追赶，" -ForegroundColor DarkGray
Write-Host "        Diff Total 先冲高后归零——这就是'消息积压'的完整生命周期。" -ForegroundColor DarkGray
Write-Host "  监控：docker exec broker sh mqadmin consumerProgress -g $Group 看 Diff Total" -ForegroundColor DarkGray
Write-Host "  消费端日志：docker logs spring-cloud-study-order-service-1 --since 5m | Select-String '积压演示'" -ForegroundColor DarkGray

exit $script:fail
