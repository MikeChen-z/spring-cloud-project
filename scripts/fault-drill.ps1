# 故障注入演练脚本（第 23 章：Alertmanager + 故障注入）
# 用法：在项目根目录执行  .\scripts\fault-drill.ps1 -Scenario gateway
# 场景：gateway | user-service | mysql | broker | seata | replica
#
# 前置：
#   1) 全栈已启动（docker compose up -d），15+2 个容器 running
#   2) 钉钉凭证已填入 docker/alertmanager/dingtalk.yml（否则告警只到 Alertmanager，不到手机）
#
# 每个场景统一骨架：
#   打印观察清单 → 停组件 → 等告警 firing（无告警场景跑业务探针观察容错行为）
#   → 提示查收钉钉 → 起组件 → 等 resolved / 验证业务自动恢复 → 小结
#
# 设计说明：
#   - gateway/user-service 用 docker compose stop/start（微服务无固定容器名，支持 scale）
#   - mysql/broker/seata 用 docker stop/start（基础设施有固定容器名，更直观）
#   - 告警等待超时 150s：scrape 15s + for:30s + group_wait 10s + 余量
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('gateway', 'user-service', 'mysql', 'broker', 'seata', 'replica')]
    [string]$Scenario
)

$baseUrl = 'http://localhost:9000'
$promUrl = 'http://localhost:9090'

function Write-Step($msg) { Write-Host "`n========== $msg ==========" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Bad($msg) { Write-Host "  [!!] $msg" -ForegroundColor Red }
function Write-Info($msg) { Write-Host "  $msg" -ForegroundColor DarkGray }

# 轮询 Prometheus 告警 API，直到指定 alertname firing（$true）或超时（$false）
function Wait-AlertFiring([string]$alertName, [int]$timeoutSec = 150) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-RestMethod -Uri "$promUrl/api/v1/alerts" -TimeoutSec 5
            $hit = @($resp.data.alerts | Where-Object { $_.labels.alertname -eq $alertName -and $_.state -eq 'firing' })
            if ($hit.Count -gt 0) { return $true }
        }
        catch { Write-Info "查询 Prometheus 失败（继续等）：$($_.Exception.Message)" }
        Start-Sleep -Seconds 5
    }
    return $false
}

# 轮询直到指定 alertname 从告警列表消失（resolved）
function Wait-AlertResolved([string]$alertName, [int]$timeoutSec = 150) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-RestMethod -Uri "$promUrl/api/v1/alerts" -TimeoutSec 5
            $hit = @($resp.data.alerts | Where-Object { $_.labels.alertname -eq $alertName })
            if ($hit.Count -eq 0) { return $true }
        }
        catch { Write-Info "查询 Prometheus 失败（继续等）：$($_.Exception.Message)" }
        Start-Sleep -Seconds 5
    }
    return $false
}

# 登录探针：$true=拿到 200
function Test-Login {
    try {
        $r = Invoke-RestMethod -Uri "$baseUrl/api/login" -Method Post -TimeoutSec 8 `
            -ContentType 'application/json; charset=utf-8' -Body '{"username":"张三","password":"123456"}'
        return ($r.code -eq 200)
    }
    catch { return $false }
}

# 登录并返回 token（下单探针用）
function Get-Token {
    $r = Invoke-RestMethod -Uri "$baseUrl/api/login" -Method Post -TimeoutSec 8 `
        -ContentType 'application/json; charset=utf-8' -Body '{"username":"张三","password":"123456"}'
    return $r.data.accessToken
}

# 查 MySQL 单值
function Get-DbValue($sql) {
    return ((docker exec mysql mysql -uroot -p123456 -N -B -e $sql 2>$null | Select-Object -First 1).Trim())
}

function Confirm-DingTalk($stage) {
    Write-Host "  >>> 请查收钉钉群：应收到一条【$stage】通知（手机/App 均可）<<<" -ForegroundColor Yellow
}

# compose 命令必须在仓库根目录执行（项目名/.env 解析）
Push-Location (Join-Path $PSScriptRoot '..')
try {
    switch ($Scenario) {

        'gateway' {
            Write-Step '场景 gateway：停网关 → InstanceDown 告警 → 恢复'
            Write-Info '预期：全部请求立即失败（网关是 9000 唯一入口）；约 1 分钟内钉钉收到 firing；start 后收到 resolved'
            docker compose stop gateway-service | Out-Null
            Write-Info '已停止 gateway-service，验证业务中断...'
            if (Test-Login) { Write-Bad '登录居然成功（不符合预期）' } else { Write-Ok '登录快速失败（符合预期：网关已断）' }
            Write-Info '等待 InstanceDown firing（scrape 15s + for 30s + group_wait 10s）...'
            if (Wait-AlertFiring 'InstanceDown') { Write-Ok 'InstanceDown 已 firing'; Confirm-DingTalk 'firing' }
            else { Write-Bad '150s 内未见 firing，检查 prometheus targets 与 alertmanager 链路' }
            docker compose start gateway-service | Out-Null
            Write-Info '已重启，等待 resolved...'
            if (Wait-AlertResolved 'InstanceDown') { Write-Ok 'InstanceDown 已 resolved'; Confirm-DingTalk 'resolved' }
            else { Write-Bad '150s 内未 resolved' }
            if (Test-Login) { Write-Ok '登录恢复 200' } else { Write-Bad '登录仍失败，请人工排查' }
        }

        'user-service' {
            Write-Step '场景 user-service：停用户服务 → UserServiceDown(absent) 告警 → 恢复'
            Write-Info '预期：dns_sd 下 A 记录消失 → absent() 触发；登录/下单依赖用户服务，应失败'
            docker compose stop user-service | Out-Null
            Start-Sleep -Seconds 5
            if (Test-Login) { Write-Bad '登录居然成功（不符合预期）' } else { Write-Ok '登录失败（符合预期）' }
            Write-Info '等待 UserServiceDown firing...'
            if (Wait-AlertFiring 'UserServiceDown') { Write-Ok 'UserServiceDown 已 firing'; Confirm-DingTalk 'firing' }
            else { Write-Bad '150s 内未见 firing' }
            docker compose start user-service | Out-Null
            Write-Info '已重启，等待注册 Nacos + resolved（服务注册需数十秒）...'
            if (Wait-AlertResolved 'UserServiceDown' 240) { Write-Ok 'UserServiceDown 已 resolved'; Confirm-DingTalk 'resolved' }
            else { Write-Bad '240s 内未 resolved' }
            Start-Sleep -Seconds 10
            if (Test-Login) { Write-Ok '登录恢复 200' } else { Write-Bad '登录仍失败' }
        }

        'mysql' {
            Write-Step '场景 mysql：停数据库 → 无实例级告警（诚实观察）→ Hikari 自动重连'
            Write-Info '预期：无告警（MySQL 无 exporter，23.8 生产建议列了 mysqld-exporter）；登录/下单 500'
            Write-Info '观察点：Grafana Explore 查 Loki：{container_name=~"spring-cloud-study-.*"} |= "CommunicationsException"'
            docker stop mysql | Out-Null
            Start-Sleep -Seconds 15
            Write-Info '已停止 mysql 15s（连接池已感知断连），执行业务探针...'
            if (Test-Login) { Write-Bad '登录居然成功（不符合预期）' } else { Write-Ok '登录 500（符合预期：DB 不可用）' }
            docker start mysql | Out-Null
            Write-Info '已重启 mysql，等待 MySQL 就绪 + Hikari 自动重连（无需重启应用）...'
            $ok = $false
            foreach ($i in 1..24) {
                Start-Sleep -Seconds 5
                if (Test-Login) { Write-Ok "登录恢复 200（第 $($i*5) 秒，Hikari 自动重连生效）"; $ok = $true; break }
            }
            if (-not $ok) { Write-Bad '120s 内未恢复' }
        }

        'broker' {
            Write-Step '场景 broker：停 RocketMQ Broker → 消息发送失败但主链路不死 → 恢复'
            Write-Info '预期：无实例级告警（broker 无 exporter）；下单主流程（Seata 扣款）不受 MQ 影响'
            Write-Info '观察点：order-service 日志里积分消息发送异常；lag-pusher.ps1 报 mqadmin 错误'
            docker stop broker | Out-Null
            Start-Sleep -Seconds 5
            $token = Get-Token
            $before = [decimal](Get-DbValue 'SELECT balance FROM study_cloud.t_user WHERE id=1')
            try {
                $r = Invoke-RestMethod -Uri "$baseUrl/api/orders/seata/create?userId=1&amount=1" -Method Post `
                    -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 15
                Write-Ok "下单接口返回 code=$($r.code)（Seata 主链路不依赖 Broker，符合预期）"
            }
            catch { Write-Info "下单返回异常：$($_.Exception.Message)（发送失败上抛也属可观察行为）" }
            $after = [decimal](Get-DbValue 'SELECT balance FROM study_cloud.t_user WHERE id=1')
            Write-Info "余额 $before -> $after"
            docker start broker | Out-Null
            Write-Info '已重启 broker，等待重新注册 NameServer...'
            $ok = $false
            foreach ($i in 1..18) {
                Start-Sleep -Seconds 5
                docker exec broker sh mqadmin brokerStatus -n namesrv:9876 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) { Write-Ok "Broker 恢复（第 $($i*5) 秒）"; $ok = $true; break }
            }
            if (-not $ok) { Write-Bad '90s 内 broker 未就绪' }
        }

        'seata' {
            Write-Step '场景 seata：停 Seata TC → 全局事务失败回滚、余额不变 → 恢复'
            Write-Info '预期：无实例级告警；Seata 下单超时/500，但余额/订单数不变（AT 回滚保护）'
            $b0 = [decimal](Get-DbValue 'SELECT balance FROM study_cloud.t_user WHERE id=1')
            docker stop seata | Out-Null
            Start-Sleep -Seconds 5
            $token = Get-Token
            try {
                $r = Invoke-RestMethod -Uri "$baseUrl/api/orders/seata/create?userId=1&amount=1" -Method Post `
                    -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 30
                Write-Bad "下单居然成功 code=$($r.code)（TC 不在不应成功）"
            }
            catch { Write-Ok '下单失败/超时（符合预期：TM 拿不到 TC）' }
            $b1 = [decimal](Get-DbValue 'SELECT balance FROM study_cloud.t_user WHERE id=1')
            if ($b1 -eq $b0) { Write-Ok "余额未变（$b0 -> $b1）：AT 回滚保护生效" } else { Write-Bad "余额变了：$b0 -> $b1" }
            docker start seata | Out-Null
            Write-Info '已重启 seata-server，等待 TC 就绪（jraft 启动较慢）...'
            Start-Sleep -Seconds 30
            try {
                $r = Invoke-RestMethod -Uri "$baseUrl/api/orders/seata/create?userId=1&amount=1" -Method Post `
                    -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 30
                if ($r.data.success) { Write-Ok '恢复后下单 200，全局事务已恢复' } else { Write-Bad "下单 code=$($r.code)" }
            }
            catch { Write-Bad "恢复后下单仍失败：$($_.Exception.Message)" }
        }

        'replica' {
            Write-Step '场景 replica：双副本杀一个 → 业务无感（高可用吸收故障，且不告警）'
            Write-Info '预期：absent 不 fire（仍有存活 A 记录）——这是特性；Nacos 15~30s 摘除故障实例'
            Write-Info '先扩容 user-service 到 2 副本...'
            docker compose up -d --scale user-service=2 | Out-Null
            Start-Sleep -Seconds 45
            $names = @(docker ps --filter 'name=user-service' --format '{{.Names}}')
            Write-Info "运行中副本：$($names -join ', ')"
            if ($names.Count -lt 2) { Write-Bad '扩容失败，未到 2 副本'; break }
            Write-Info "停掉副本 $($names[0])..."
            docker stop $names[0] | Out-Null
            Start-Sleep -Seconds 20
            $pass = 0
            foreach ($i in 1..5) {
                if (Test-Login) { $pass++ }
                Start-Sleep -Seconds 3
            }
            if ($pass -eq 5) { Write-Ok '5/5 次登录全部成功：网关已只路由到存活副本，业务无感' }
            else { Write-Bad "登录成功 $pass/5（Nacos 摘除有延迟，前几次失败可接受）" }
            Write-Info '观察 Nacos 控制台 http://localhost:8848/nacos ：user-service 实例数 2 → 1'
            Write-Info '观察 /alerts：UserServiceDown 全程未 fire（多副本吸收故障，正确行为）'
            Write-Info '缩容回 1 副本...'
            docker compose up -d --scale user-service=1 | Out-Null
            Start-Sleep -Seconds 10
            if (Test-Login) { Write-Ok '缩容后登录 200' } else { Write-Bad '缩容后登录失败' }
        }
    }
    Write-Step "场景 $Scenario 演练完成"
}
finally {
    Pop-Location
}