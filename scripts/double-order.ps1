# 顺序消息验证：两个订单并发发送，各 6 个状态按 1->6 严格保序，订单之间允许交错
# 运行环境：PowerShell 7 (pwsh)——默认全链路 UTF-8，无需 PS 5.1 的编码补丁

$login = Invoke-RestMethod -Uri "http://localhost:9000/api/login" -Method Post -ContentType "application/json" -Body '{"username":"张三","password":"123456"}'
$token = $login.data.accessToken
$h = @{ Authorization = "Bearer $token" }

Invoke-RestMethod "http://localhost:9000/api/orders/status-demo?orderId=301" -Method Post -Headers $h | Out-Null
Invoke-RestMethod "http://localhost:9000/api/orders/status-demo?orderId=302" -Method Post -Headers $h | Out-Null

$matched = @(docker logs spring-cloud-study-order-service-1 --since 5m 2>&1 | Select-String '顺序消息')
Write-Output "----- 顺序消息日志 $($matched.Count) 条 -----"
$matched | ForEach-Object { $_.Line -replace '.*?"message":"', '' -replace '","logger_name.*', '' }
