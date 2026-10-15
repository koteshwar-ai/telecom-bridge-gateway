<#
.SYNOPSIS
  One-command PCAP capture for the Telecom-Bridge gateway on Windows.

.DESCRIPTION
  Builds the jar, starts the Diameter simulator, starts a loopback capture (Wireshark's dumpcap + Npcap),
  starts the gateway (so CER/CEA is in the capture), sends N REST charge calls (each producing a CCR/CCA),
  and saves transaction_flow.pcap in the project root (real libpcap format, not pcapng).

.PREREQUISITES
  * Wireshark installed (it bundles dumpcap and Npcap; keep "loopback support" ticked)
  * Java 21 on PATH or JAVA_HOME set
  * Run PowerShell as Administrator if the capture fails to start

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\capture-pcap.ps1
  powershell -ExecutionPolicy Bypass -File scripts\capture-pcap.ps1 -Requests 10 -CaptureSeconds 60
#>
param(
    [int]$Requests = 10,
    [int]$CaptureSeconds = 45,
    [string]$Output = "transaction_flow.pcap",
    [string]$DumpCap = "C:\Program Files\Wireshark\dumpcap.exe",
    [string]$TShark = "C:\Program Files\Wireshark\tshark.exe"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root
$logs = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$pcap = Join-Path $root $Output
if (Test-Path $pcap) { Remove-Item $pcap -Force }

function Wait-Port([int]$Port, [int]$TimeoutSec = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $c = New-Object System.Net.Sockets.TcpClient
            $c.Connect("127.0.0.1", $Port)
            $c.Close()
            return $true
        } catch { Start-Sleep -Milliseconds 500 }
    }
    return $false
}

function Wait-Log([string]$Path, [string]$Pattern, [int]$TimeoutSec = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $Path) {
            try { if (Select-String -Path $Path -Pattern $Pattern -Quiet) { return $true } } catch { }
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

if (-not (Test-Path $DumpCap)) {
    throw "dumpcap.exe not found at '$DumpCap'. Install Wireshark (with Npcap) or pass -DumpCap <path>."
}

# ---- 1. find the Npcap loopback interface ----
$loop = (& $DumpCap -D) | Where-Object { $_ -match "Loopback" } | Select-Object -First 1
if (-not $loop) {
    throw "No Npcap loopback interface found. Reinstall Npcap with 'Support loopback traffic' ticked."
}
$ifaceNo = ($loop -split "\.")[0].Trim()
Write-Host "Capturing on interface $ifaceNo : $loop"

# ---- 2. build ----
Write-Host "Building jar..."
& (Join-Path $root "mvnw.cmd") -q -B package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
$jar = (Get-ChildItem (Join-Path $root "target") -Filter "*.jar" |
        Where-Object { $_.Name -notmatch "original|sources|javadoc" } | Select-Object -First 1).FullName
if (-not $jar) { throw "No jar found in target\" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }

$sim = $null; $gw = $null; $cap = $null
try {
    # ---- 3. simulator first, so the gateway can complete CER/CEA while we are recording ----
    Write-Host "Starting Diameter simulator on :3868 ..."
    $simArgs = @("-Dloader.main=com.pyrogroup.telecom_bridge_gateway.simulator.SimulatorMain",
                 "-cp", "`"$jar`"", "org.springframework.boot.loader.launch.PropertiesLauncher")
    $sim = Start-Process $java -ArgumentList $simArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs "simulator.log") -RedirectStandardError (Join-Path $logs "simulator.err")
    if (-not (Wait-Port 3868 30)) { throw "Simulator did not open port 3868 (see logs\simulator.log)" }

    # ---- 4. start recording ----
    Write-Host "Starting capture (auto-stops after $CaptureSeconds s) -> $pcap"
    $capArgs = @("-i", $ifaceNo, "-f", "`"tcp port 3868 or tcp port 8080`"", "-F", "pcap",
                 "-a", "duration:$CaptureSeconds", "-w", "`"$pcap`"")
    $cap = Start-Process $DumpCap -ArgumentList $capArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardError (Join-Path $logs "dumpcap.log")
    Start-Sleep -Seconds 3
    if ($cap.HasExited) {
        throw "dumpcap exited immediately - run PowerShell as Administrator (see logs\dumpcap.log)"
    }

    # ---- 5. gateway (connects -> CER/CEA lands in the capture). 127.0.0.1 avoids IPv6 ::1 confusion ----
    Write-Host "Starting gateway on :8080 ..."
    $env:DIAMETER_HOST = "127.0.0.1"
    $gw = Start-Process $java -ArgumentList @("-jar", "`"$jar`"") -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs "gateway.log") -RedirectStandardError (Join-Path $logs "gateway.err")
    if (-not (Wait-Log (Join-Path $logs "gateway.log") "Diameter connection OPEN" 90)) {
        throw "Gateway never reported 'Diameter connection OPEN' (see logs\gateway.log)"
    }

    # ---- 6. REST calls -> CCR/CCA ----
    Write-Host "Sending $Requests charge requests..."
    $ok = 0
    for ($i = 1; $i -le $Requests; $i++) {
        $body = @{ msisdn = ("9198765432{0:D2}" -f $i); units = 1024 } | ConvertTo-Json -Compress
        try {
            $r = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/v1/charge" `
                -ContentType "application/json" -Body $body
            Write-Host ("  [{0}] {1} resultCode={2} granted={3}" -f $i, $r.status, $r.resultCode, $r.grantedUnits)
            $ok++
        } catch {
            Write-Warning "  request $i failed: $($_.Exception.Message)"
        }
        Start-Sleep -Milliseconds 300
    }
    Write-Host "$ok / $Requests requests succeeded."

    # ---- 7. let dumpcap stop by itself so the file is closed cleanly ----
    Write-Host "Waiting for the capture to finish and flush..."
    try { $cap | Wait-Process -Timeout ($CaptureSeconds + 20) } catch { Write-Warning "dumpcap still running" }
}
finally {
    foreach ($p in @($gw, $sim, $cap)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
}

# ---- 8. report ----
if (-not (Test-Path $pcap)) { throw "No pcap was produced - see logs\dumpcap.log" }
Write-Host ("`nSaved {0} ({1:N1} KB)" -f $pcap, ((Get-Item $pcap).Length / 1KB))
if (Test-Path $TShark) {
    Write-Host "`nDiameter / HTTP packets in the capture:"
    & $TShark -r $pcap -Y "diameter || http" 2>$null
} else {
    Write-Host "Open it in Wireshark and use the display filter:  diameter || http"
}
