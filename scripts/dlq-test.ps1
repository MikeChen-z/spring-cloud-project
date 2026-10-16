# ============================================================
# 死信队列验证：毒消息 → 重试耗尽 → 死信 Topic → 落库可见 → 人工补偿重投 → 消费成功回写
# 用法：在项目根目录执行
#       pwsh -File .\scripts\dlq-test.ps1 [-Count 3]
# 前置：docker compose up -d（order-service 单实例即可），网关 9000 可访问（脚本自带登录）
# 运行环境：PowerShell 7 (pwsh)——默认全链路 UTF-8
#
# 覆盖三个诉求：
#   可见    —— 死信落库 t_dead_letter，接口可查；Broker 死信 Topic 也有消息
#   可补偿  —— 重投接口读库 → 修复 → 重投原 Topic，消费成功后回写 status=1
#   不丢业务 —— 原始报文（body）持久化在 MySQL，不依赖 Broker 死信保留期
#
# 关键事实（见 order-service BacklogConsumer / DlqConsumer）：
#   - 死信 Topic 名固定 %DLQ%<consumerGroup>，由 Broker 自动创建，无需人工建
#   - 毒消息必须走【并发消费】：顺序消费失败会无限挂起 queue，永不进死信
#   - 加速开关：BacklogConsumer.maxReconsumeTimes=2，约 10s + 30s 退避后耗尽进死信
#
# 实测参考：3 条毒消息约 45s 进死信；补偿重投后 <1s 消费成功并回写 status=1
# ============================================================
param(
    [int]$Count = 3,
    [int]$PoisonSleepMs = -1,          # 毒消息标记：负的 sleepMs 让消费者抛异常
    [int]$FixSleepMs = 0,              # 补偿时的修复值：覆盖掉导致失败的 sleepMs
    [string]$Base = "http://localhost:9000",
    [string]$Username = "张三",
    [string]$Password = "123456",
    [string]$Group = "order-backlog-consumer-group",
    [string]$DlqGroup = "order-dlq-consumer-group",
    [int]$DlqTimeoutSec = 120,         # 等待毒消息重试耗尽进死信的上限
    [int]$CompensateTimeoutSec = 30    # 等待补偿重投被消费、回写状态的上限
)

$ErrorActionPreference = "Stop"
$DlqTopic = "%DLQ%$Group"

# ---------- 辅助：读 Broker 侧死信 Topic 的 Max Offset（该 Topic 累计写入的消息数） ----------
function Get-DlqMaxOffset {
    $raw = (docker exec broker sh mqadmin topicStatus -t $DlqTopic -n namesrv:9876 2>&1) -join "`n"
    $max = 0
    foreach ($line in ($raw -split "`n")) {
        # 数据行形如：broker-a   0   0   3   2026-10-15 02:24:03,933  —— 第 4 列是 Max Offset
        if ($line -match '^\s*(\S+)\s+(\d+)\s+(\d+)\s+(\d+)\s+' -and -not $line.StartsWith("#")) {
            $v = [int]$Matches[4]
            if ($v -gt $max) { $max = $v }
        }
    }
    return $max
}

# ---------- 辅助：死信消费组的积压 Diff（>0 表示死信还没来得及落库） ----------
function Get-DlqDiff {
    $raw = (docker exec broker sh mqadmin consumerProgress -g $DlqGroup -n namesrv:9876 2>&1) -join "`n"
    if ($raw -match 'Diff Total:\s*(\d+)') { return [int]$Matches[1] }
    return -1   # -1 = 消费组尚不存在（无消息进过死信）
}

# ---------- 辅助：查死信记录（status 为 $null 时查全部） ----------
function Get-DeadLetters($token, $status) {
    $h = @{ Authorization = "Bearer $token" }
    $uri = if ($null -eq $status) { "$Base/api/orders/dlq/list?limit=200" } else { "$Base/api/orders/dlq/list?status=$status&limit=200" }
    $res = Invoke-RestMethod -Uri $uri -Headers $h
    return @($res.data)
}

Write-Host "== 死信队列验证：灌入 $Count 条毒消息（sleepMs=$PoisonSleepMs）==" -ForegroundColor Cyan

# 1. 登录
$login = Invoke-RestMethod -Uri "$Base/api/login" -Method Post -ContentType "application/json" `
    -Body "{`"username`":`"$Username`",`"password`":`"$Password`"}"
$token = $login.data.accessToken
$h = @{ Authorization = "Bearer $token" }
Write-Host "登录成功" -ForegroundColor Green

# 2. 记录起始水位（按 id 增量判断本轮新增，避免受历史数据干扰）
$beforeList = Get-DeadLetters $token $null
$beforeMaxId = if ($beforeList.Count -gt 0) { ($beforeList | Measure-Object -Property id -Maximum).Maximum } else { 0 }
$beforeOffset = Get-DlqMaxOffset
Write-Host ("起始：已存在死信记录 {0} 条（max id={1}），死信 Topic Max Offset={2}" -f $beforeList.Count, $beforeMaxId, $beforeOffset)

# 3. 灌入毒消息（sendOneWay 瞬时完成；负 sleepMs 即毒消息标记）
$sendRes = Invoke-RestMethod -Uri "$Base/api/orders/backlog-demo?count=$Count&sleepMs=$PoisonSleepMs" `
    -Method Post -Headers $h -SkipHttpErrorCheck
if ($null -eq $sendRes.data) {
    Write-Host "  灌入接口未正常返回 body（可能网关 2s 超时），消息仍可能已发出，继续等待死信" -ForegroundColor Yellow
}
Write-Host "已灌入 $Count 条毒消息，等待重试耗尽后转入死信（约 10s + 30s 退避）..." -ForegroundColor Yellow

# 4. 轮询等待死信落库（并发消费重试耗尽 → Broker 自动投 mark 到 %DLQ%）
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$newRecords = @()
$deadline = (Get-Date).AddSeconds($DlqTimeoutSec)
while ((Get-Date) -lt $deadline) {
    $all = Get-DeadLetters $token $null
    $newRecords = @($all | Where-Object { $_.id -gt $beforeMaxId })
    Write-Host ("  t={0,5}ms  新增死信={1}/{2}  (Broker MaxOffset={3})" -f `
            $sw.ElapsedMilliseconds, $newRecords.Count, $Count, (Get-DlqMaxOffset)) -ForegroundColor DarkGray
    if ($newRecords.Count -ge $Count) { break }
    Start-Sleep -Seconds 3
}
$sw.Stop()
$dlqSec = [Math]::Round($sw.Elapsed.TotalSeconds, 2)
$afterOffset = Get-DlqMaxOffset

# 5. 断言一：可见 + 不丢业务（死信已落库、原报文完整、Broker 侧也可见）
$bodiesOk = @($newRecords | Where-Object { $_.body -like "*`"sleepMs`":$PoisonSleepMs*" }).Count
$allPending = @($newRecords | Where-Object { $_.status -eq 0 }).Count
$topicOk = @($newRecords | Where-Object { $_.topic -eq "order-backlog-topic" -and $_.consumerGroup -eq $Group }).Count

# 6. 断言二：可补偿（重投接口）
$retryRes = Invoke-RestMethod -Uri "$Base/api/orders/dlq/retry?limit=$Count&sleepMs=$FixSleepMs" -Method Post -Headers $h

# 7. 轮询等待补偿被消费、状态回写为 1（可补偿闭环真正走通）
$csw = [System.Diagnostics.Stopwatch]::StartNew()
$finalNew = @()
$cdeadline = (Get-Date).AddSeconds($CompensateTimeoutSec)
while ((Get-Date) -lt $cdeadline) {
    $finalAll = Get-DeadLetters $token $null
    $finalNew = @($finalAll | Where-Object { $_.id -gt $beforeMaxId })
    $done = @($finalNew | Where-Object { $_.status -eq 1 }).Count
    Write-Host ("  t={0,5}ms  补偿成功={1}/{2}" -f $csw.ElapsedMilliseconds, $done, $newRecords.Count) -ForegroundColor DarkGray
    if ($done -ge $newRecords.Count) { break }
    Start-Sleep -Seconds 2
}
$csw.Stop()
$status1 = @($finalNew | Where-Object { $_.status -eq 1 }).Count
$retried = @($finalNew | Where-Object { $_.retryCount -ge 1 }).Count
$dlqDiff = Get-DlqDiff

# ---------- 断言 ----------
$script:pass = 0; $script:fail = 0
function Check($name, $cond, $detail) {
    if ($cond) { Write-Host "  [PASS] $name  $detail" -ForegroundColor Green; $script:pass++ }
    else { Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red; $script:fail++ }
}

Write-Host "`n== 结果 ==" -ForegroundColor Cyan
Write-Host ("  毒消息 {0} 条于 {1}s 后进入死信；补偿重投 {2} 条，{3}s 内回写成功" -f $Count, $dlqSec, $retryRes.data.resent, [Math]::Round($csw.Elapsed.TotalSeconds, 2))
Write-Host ("  Broker 死信 Topic {0}：MaxOffset {1} → {2}" -f $DlqTopic, $beforeOffset, $afterOffset)

Check "可见：毒消息重试耗尽后全部落入死信并落库" ($newRecords.Count -ge $Count) "新增死信记录=$($newRecords.Count)/$Count"
Check "可见：Broker 死信 Topic 同步增长" ($afterOffset -ge $beforeOffset + $Count) "MaxOffset $beforeOffset → $afterOffset"
Check "可见：死信消费组已消费（Diff=0，全部落库）" ($dlqDiff -eq 0) "Diff Total=$dlqDiff"
Check "不丢业务：原始报文完整保存（含 sleepMs=$PoisonSleepMs）" ($bodiesOk -ge $Count) "body 完整=$bodiesOk/$Count"
Check "可见：来源 topic/消费组记录正确" ($topicOk -ge $Count) "topic=order-backlog-topic 且 group=$Group 的记录=$topicOk/$Count"
Check "补偿前：新增记录处于待补偿(status=0)" ($allPending -ge $Count) "status=0 记录=$allPending/$Count"
Check "可补偿：重投接口读库并成功重投" ($retryRes.data.resent -ge $Count -and @($retryRes.data.failures).Count -eq 0) "resent=$($retryRes.data.resent)/$Count 失败=$(@($retryRes.data.failures).Count)"
Check "可补偿闭环：重投消息消费成功并回写 status=1" ($status1 -ge $Count) ("status=1 记录={0}/{1}（{2}s）" -f $status1, $Count, [Math]::Round($csw.Elapsed.TotalSeconds, 2))
Check "可追溯：记录累计重投次数" ($retried -ge $Count) "retryCount>=1 记录=$retried/$Count"

Write-Host ""
Write-Host "  结论：毒消息无法消费 → 重试耗尽 → 自动进入 %DLQ%$Group（Broker 自动建 Topic）→" -ForegroundColor DarkGray
Write-Host "        落库 t_dead_letter（可见、持久、不丢业务）→ 调重投接口修复后重发 →" -ForegroundColor DarkGray
Write-Host "        消费者成功处理并回写 status=1 —— 死信闭环完成。" -ForegroundColor DarkGray
Write-Host "  死信列表：curl -H 'Authorization: Bearer <token>' $Base/api/orders/dlq/list?status=0" -ForegroundColor DarkGray
Write-Host "  Broker 侧：docker exec broker sh mqadmin topicStatus -t $DlqTopic -n namesrv:9876" -ForegroundColor DarkGray
Write-Host "  应用日志：docker logs spring-cloud-study-order-service-1 --since 5m | Select-String '死信'" -ForegroundColor DarkGray

exit $script:fail
