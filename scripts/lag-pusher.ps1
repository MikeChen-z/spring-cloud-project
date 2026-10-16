# ============================================================
# RocketMQ 积压推送器：定期从 broker 自带的 mqadmin 拉取消费组 Diff Total，
#                       POST 到 order-service 的 /orders/internal/lag-report
#                       → Micrometer Gauge 更新 → Prometheus 抓取 → 告警规则评估
# 用法（手动后台挂起，学习环境最简模式）：
#       Start-Process pwsh -ArgumentList '-File .\scripts\lag-pusher.ps1' -WindowStyle Hidden
# 或者前台运行看输出：
#       pwsh -File .\scripts\lag-pusher.ps1 [-IntervalSec 15]
# 前置：docker compose up -d（broker + order-service 就绪）
#       此脚本必须在宿主机运行（order-service 容器没有 docker CLI）
#
# 关键事实：
#   broker 4.9.7 自带的 mqadmin consumerProgress 与 broker 版本天然匹配；
#   Java 侧用 rocketmq-tools 5.1.x 会发 5.x 协议（request type 351）被 broker 4.9.7 拒绝。
#   所以 Java 侧放弃主动拉取，改为宿主机脚本拉取后推送。
#
# broker 启动 ~5s 后 mqadmin 才可执行（JVM 冷启动），本脚本首循环前 sleep 5s 缓冲。
# ============================================================
param(
    [int]$IntervalSec = 15,
    [string]$BrokerContainer = "broker",
    [string]$NameServer = "namesrv:9876",
    [string]$OrderServiceBase = "http://localhost:9000/api"
)

$ErrorActionPreference = "Stop"

# 硬编码消费组列表（与 MqLagMonitor.INITIAL_GROUPS 保持一致）
$Consumers = @(
    "order-backlog-consumer-group",
    "order-status-consumer-group",
    "order-delay-consumer-group",
    "order-dlq-consumer-group"
)

Write-Host "=== lag-pusher 启动 ===" -ForegroundColor Cyan
Write-Host "  间隔 ${IntervalSec}s"
Write-Host "  broker = $BrokerContainer  namesrv = $NameServer"
Write-Host "  推送目标 = ${OrderServiceBase}/orders/internal/lag-report"
Write-Host "  消费组 = $($Consumers -join ', ')"
Write-Host ""

# 首循环前缓冲 broker JVM 启动
Start-Sleep -Seconds 5

$iter = 0
while ($true) {
    $iter++
    $groupDiffs = @{}

    foreach ($group in $Consumers) {
        try {
            # mqadmin 每次起 JVM 约 3.5s，超时 8s 足够
            $raw = & docker exec $BrokerContainer sh mqadmin consumerProgress -g $group -n $NameServer 2>&1
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  [$group] mqadmin 退出码 $LASTEXITCODE，跳过（broker 可能刚启动）" -ForegroundColor Yellow
                $groupDiffs[$group] = 0
                continue
            }
            # 解析最后一行 "Diff Total: 1540"
            $diffLine = ($raw | Select-String -Pattern "Diff Total:\s*(\d+)" -List)
            if ($diffLine) {
                $diff = [long]$diffLine.Matches[0].Groups[1].Value
            } else {
                Write-Host "  [$group] 未找到 Diff Total 行，默认 0" -ForegroundColor Yellow
                $diff = 0
            }
            $groupDiffs[$group] = $diff
        } catch {
            Write-Host "  [$group] 异常：$($_.Exception.Message)" -ForegroundColor Yellow
            $groupDiffs[$group] = 0
        }
    }

    # POST 到 order-service（无鉴权，localhost 内部接口）
    try {
        $json = $groupDiffs | ConvertTo-Json -Compress
        $r = Invoke-RestMethod -Method Post -Uri "${OrderServiceBase}/orders/internal/lag-report" -ContentType "application/json" -Body $json
        $total = $r.data.totalDiff
        if ($total -gt 0) {
            Write-Host "[$iter] 推送成功  合计积压 $total 条  $json" -ForegroundColor Cyan
        } else {
            Write-Host "[$iter] 推送成功  全部积压为 0" -ForegroundColor Gray
        }
    } catch {
        Write-Host "[$iter] 推送失败：$($_.Exception.Message)" -ForegroundColor Red
    }

    Start-Sleep -Seconds $IntervalSec
}
