param(
    [string]$ProjectRoot = "D:\MyProjrct",
    [string]$MysqlBin = "D:\JavaDevelop\mysql-8.0.28-winx64\bin\mysql.exe",
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "123456",
    [string]$MysqlDb = "school_ticket",
    [string]$MavenBin = "D:\JavaDevelop\maven-3.9.6\bin\mvn.cmd",
    [int]$BackendPort = 8080,
    [switch]$SkipBackendRestart
)

$ErrorActionPreference = "Stop"

function Write-Step($Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Stop-PortProcess($Port) {
    $connections = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue |
        Where-Object { $_.State -eq "Listen" }
    foreach ($connection in $connections) {
        Write-Host "Stopping process on port ${Port}: pid=$($connection.OwningProcess)"
        Stop-Process -Id $connection.OwningProcess -Force -ErrorAction SilentlyContinue
    }
}

function Wait-Port($Port, $TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $listening = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue |
            Where-Object { $_.State -eq "Listen" }
        if ($listening) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Wait-Docker($TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Invoke-QueuePurge($QueueName) {
    docker exec rabbitmq rabbitmqctl purge_queue $QueueName *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Purged RabbitMQ queue: $QueueName"
    } else {
        Write-Host "Queue purge skipped/failed: $QueueName" -ForegroundColor Yellow
    }
}

$InitSql = Join-Path $ProjectRoot "backend\init.sql"
$BackendDir = Join-Path $ProjectRoot "backend"
$BackendOut = Join-Path $ProjectRoot "backend-codex-run.out.log"
$BackendErr = Join-Path $ProjectRoot "backend-codex-run.err.log"

Write-Step "Checking paths"
if (-not (Test-Path $ProjectRoot)) { throw "ProjectRoot not found: $ProjectRoot" }
if (-not (Test-Path $MysqlBin)) { throw "MysqlBin not found: $MysqlBin" }
if (-not (Test-Path $InitSql)) { throw "init.sql not found: $InitSql" }
if (-not (Test-Path $MavenBin)) { throw "MavenBin not found: $MavenBin" }

Write-Step "Ensuring Docker containers are running"
if (-not (Wait-Docker)) {
    throw "Docker daemon is not ready. Start Docker Desktop first."
}
docker start redis *> $null
docker start rabbitmq *> $null
Start-Sleep -Seconds 3

Write-Step "Stopping backend on port $BackendPort"
Stop-PortProcess $BackendPort
Start-Sleep -Seconds 2

Write-Step "Rebuilding MySQL test database"
$mysqlArgs = "-u $MysqlUser -p$MysqlPassword --default-character-set=utf8mb4 -D $MysqlDb < `"$InitSql`""
cmd /c "`"$MysqlBin`" $mysqlArgs"
if ($LASTEXITCODE -ne 0) {
    throw "MySQL rebuild failed."
}

Write-Step "Flushing Redis and recreating order stream group"
docker exec redis redis-cli FLUSHALL
docker exec redis redis-cli XGROUP CREATE stream:orders order-consumers '$' MKSTREAM

Write-Step "Purging RabbitMQ queues"
$queues = @(
    "order.create.queue",
    "order.delay.queue",
    "order.close.queue",
    "order.dead.queue",
    "note.like.queue",
    "note.create.queue",
    "note.delete.queue",
    "user.follow.queue"
)
foreach ($queue in $queues) {
    Invoke-QueuePurge $queue
}

if (-not $SkipBackendRestart) {
    Write-Step "Starting backend"
    Remove-Item -LiteralPath $BackendOut,$BackendErr -Force -ErrorAction SilentlyContinue
    Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c", "`"$MavenBin`" spring-boot:run" `
        -WorkingDirectory $BackendDir `
        -RedirectStandardOutput $BackendOut `
        -RedirectStandardError $BackendErr `
        -WindowStyle Hidden

    if (-not (Wait-Port $BackendPort 90)) {
        Get-Content -Path $BackendOut -Tail 80 -ErrorAction SilentlyContinue
        Get-Content -Path $BackendErr -Tail 80 -ErrorAction SilentlyContinue
        throw "Backend did not listen on port $BackendPort within timeout."
    }

    Write-Step "Warming event endpoint"
    curl.exe -s "http://localhost:$BackendPort/api/v1/event/list?status=1&page=1&pageSize=1" | Out-Null
}

Write-Step "Reset complete"
Write-Host "Backend: http://localhost:$BackendPort"
Write-Host "Run verify: node .\tests\seckill\verify.mjs --ticket-id 21 --event-id 8"
