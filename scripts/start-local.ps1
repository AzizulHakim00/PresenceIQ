# PresenceIQ local Windows startup helper (PowerShell 5.1+).
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1
# To enable the (large) camera inference service after placing models/best.pt:
#   powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Vision

param([switch]$Vision)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

Write-Host ""
Write-Host "PresenceIQ | Local startup checks" -ForegroundColor Cyan
Write-Host "Folder: $repo"
Write-Host ""

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "Docker CLI was not found. Install Docker Desktop and reopen PowerShell." -ForegroundColor Red
    exit 1
}
docker info --format "{{.ServerVersion}}" 1>$null 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker Engine is not reachable. Start Docker Desktop and wait until it reports running." -ForegroundColor Red
    exit 2
}
docker compose version
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker Compose v2 is required." -ForegroundColor Red
    exit 3
}

if (-not (Test-Path ".env")) {
    Write-Host "Creating a private .env with random development credentials..."
    if (Get-Command py -ErrorAction SilentlyContinue) {
        & py -3 scripts/setup_dev.py
    }
    elseif (Get-Command python -ErrorAction SilentlyContinue) {
        & python scripts/setup_dev.py
    }
    else {
        Write-Host "Python is needed once to generate private credentials. Install Python 3 or create .env manually." -ForegroundColor Red
        exit 4
    }
    if ($LASTEXITCODE -ne 0) { exit 4 }
}

if ($Vision -and -not (Test-Path "models/best.pt")) {
    Write-Host "The live vision profile needs models/best.pt. Copy your approved YOLO model checkpoint first." -ForegroundColor Red
    exit 5
}
docker compose config -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "Compose configuration is invalid. Check .env values without sharing them publicly." -ForegroundColor Red
    exit 6
}

Write-Host ""
Write-Host "Starting MongoDB, Spring Boot API, and frontend..." -ForegroundColor Cyan
if ($Vision) {
    docker compose --profile vision up -d --build
}
else {
    docker compose up -d --build
}
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build/startup failed. Inspect the errors above. No data has been deleted." -ForegroundColor Red
    docker compose ps --all
    exit 7
}

# A service can say "Up" while its port is NOT published to Windows.
# Read the effective Compose mapping without printing .env credentials.
$binding = @(docker compose port api 8080 2>$null) -join ""
if ($binding -notmatch ':8080
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod -TimeoutSec 4 -Uri "http://127.0.0.1:8080/api/health"
        if ($health.status -eq "ok") { $ready = $true; break }
    }
    catch { }
}
Write-Host ""
docker compose ps --all
if (-not $ready) {
    Write-Host "API port is published, but the health endpoint did not respond." -ForegroundColor Red
    Write-Host "Check: docker compose logs --tail 100 api mongodb" -ForegroundColor Red
    Write-Host "Remove any passwords, connection strings, RTSP URLs, or private information before sharing logs."
    exit 8
}
try {
    $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Uri "http://127.0.0.1:5173"
    if ($response.StatusCode -ne 200) { throw "Frontend status: $($response.StatusCode)" }
}
catch {
    Write-Host "API is running, but the frontend is unreachable. Check: docker compose logs --tail 100 web" -ForegroundColor Yellow
    exit 9
}

Write-Host ""
Write-Host "SUCCESS: Dashboard http://localhost:5173" -ForegroundColor Green
Write-Host "API: http://localhost:8080/api/health"
Write-Host "Your admin password is in the private .env file. Do not share it."
if (-not $Vision) {
    Write-Host "Camera inference is OFF for initial startup. Enable later with -Vision (and models/best.pt)."
}
) {
    Write-Host ""
    Write-Host "The API container is running without host port 8080 published." -ForegroundColor Yellow
    Write-Host "Recreating only the API container to restore the port binding (MongoDB data will be preserved)..."
    docker compose up -d --no-deps --force-recreate api
    if ($LASTEXITCODE -ne 0) {
        Write-Host "API recreation failed. Check for conflicting local Compose override files or port 8080 already in use." -ForegroundColor Red
        docker compose ps --all
        exit 10
    }
    $binding = @(docker compose port api 8080 2>$null) -join ""
    if ($binding -notmatch ':8080
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod -TimeoutSec 4 -Uri "http://127.0.0.1:8080/api/health"
        if ($health.status -eq "ok") { $ready = $true; break }
    }
    catch { }
}
Write-Host ""
docker compose ps --all
if (-not $ready) {
    Write-Host "API did not become healthy. Check: docker compose logs --tail 100 api mongodb" -ForegroundColor Red
    Write-Host "Remove any passwords, connection strings, RTSP URLs, or private information before sharing logs."
    exit 8
}
try {
    $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Uri "http://127.0.0.1:5173"
    if ($response.StatusCode -ne 200) { throw "Frontend status: $($response.StatusCode)" }
}
catch {
    Write-Host "API is running, but the frontend is unreachable. Check: docker compose logs --tail 100 web" -ForegroundColor Yellow
    exit 9
}

Write-Host ""
Write-Host "SUCCESS: Dashboard http://localhost:5173" -ForegroundColor Green
Write-Host "API: http://localhost:8080/api/health"
Write-Host "Your admin password is in the private .env file. Do not share it."
if (-not $Vision) {
    Write-Host "Camera inference is OFF for initial startup. Enable later with -Vision (and models/best.pt)."
}
) {
        Write-Host "API still has no Windows host port mapping. Expected 127.0.0.1:8080 -> 8080/tcp." -ForegroundColor Red
        Write-Host "Check local compose.override.yml and run these SAFE commands:"
        Write-Host '  docker compose port api 8080'
        Write-Host '  docker compose ps --all'
        Write-Host '  $c = docker compose config --format json | ConvertFrom-Json; $c.services.api.ports | Format-Table host_ip,published,target'
        Write-Host "Do not share the full docker compose config output; it may reveal secrets."
        exit 11
    }
}
Write-Host "API host port mapping: $binding" -ForegroundColor Green

$ready = $false
for ($attempt=0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod -TimeoutSec 4 -Uri "http://127.0.0.1:8080/api/health"
        if ($health.status -eq "ok") { $ready = $true; break }
    }
    catch { }
}
Write-Host ""
docker compose ps --all
if (-not $ready) {
    Write-Host "API did not become healthy. Check: docker compose logs --tail 100 api mongodb" -ForegroundColor Red
    Write-Host "Remove any passwords, connection strings, RTSP URLs, or private information before sharing logs."
    exit 8
}
try {
    $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Uri "http://127.0.0.1:5173"
    if ($response.StatusCode -ne 200) { throw "Frontend status: $($response.StatusCode)" }
}
catch {
    Write-Host "API is running, but the frontend is unreachable. Check: docker compose logs --tail 100 web" -ForegroundColor Yellow
    exit 9
}

Write-Host ""
Write-Host "SUCCESS: Dashboard http://localhost:5173" -ForegroundColor Green
Write-Host "API: http://localhost:8080/api/health"
Write-Host "Your admin password is in the private .env file. Do not share it."
if (-not $Vision) {
    Write-Host "Camera inference is OFF for initial startup. Enable later with -Vision (and models/best.pt)."
}
