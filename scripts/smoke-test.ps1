param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MailpitUrl = "http://localhost:8025",
    [switch]$CheckRestart
)

$ErrorActionPreference = "Stop"

function Wait-Health {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 3
            if ($health.status -eq "UP") { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Health did not become UP at $BaseUrl"
}

function Invoke-Json {
    param($Method, $Url, $Body)
    $headers = @{ "Content-Type" = "application/json" }
    try {
        if ($null -eq $Body) {
            return Invoke-WebRequest -Method $Method -Uri $Url -Headers $headers -UseBasicParsing
        }
        return Invoke-WebRequest -Method $Method -Uri $Url -Headers $headers -Body $Body -UseBasicParsing
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        $status = [int]$response.StatusCode
        $stream = $response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $content = $reader.ReadToEnd()
        $reader.Close()
        return [pscustomobject]@{ StatusCode = $status; Content = $content }
    }
}

Wait-Health
Invoke-RestMethod -Method Delete -Uri "$MailpitUrl/api/v1/messages" | Out-Null

$sku = "SMOKE-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$order1 = "O1-$sku"
$order2 = "O2-$sku"
$flashOrder1 = "F1-$sku"
$flashOrder2 = "F2-$sku"
$overOrder = "O9-$sku"
Invoke-Json PUT "$BaseUrl/api/v1/products/$sku" '{"category":"STANDARD"}' | Out-Null
$stock = Invoke-Json POST "$BaseUrl/api/v1/products/$sku/stock" '{"quantity":10}'
if ($stock.StatusCode -ne 200) { throw "add stock failed $($stock.StatusCode)" }

$r1 = Invoke-Json POST "$BaseUrl/api/v1/reservations" (@{ orderId = $order1; sku = $sku; quantity = 5 } | ConvertTo-Json)
if ($r1.StatusCode -ne 200) { throw "reserve failed $($r1.StatusCode) $($r1.Content)" }
$r2 = Invoke-Json POST "$BaseUrl/api/v1/reservations" (@{ orderId = $order1; sku = $sku; quantity = 5 } | ConvertTo-Json)
if ($r2.StatusCode -ne 200) { throw "replay failed" }
if ($r1.Content -ne $r2.Content) { throw "replay body differs" }

$avail = Invoke-RestMethod "$BaseUrl/api/v1/products/$sku/availability"
if ($avail.available -ne 5) { throw "expected available 5, got $($avail.available)" }

$deadline = (Get-Date).AddSeconds(15)
$mailCount = 0
do {
    $messages = Invoke-RestMethod "$MailpitUrl/api/v1/messages"
    $mailCount = $messages.total
    if ($mailCount -eq 1 -and ($messages.messages | Where-Object { $_.Subject -like "*$sku*" })) { break }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)
if ($mailCount -ne 1) { throw "expected 1 mail, got $mailCount" }

$r3 = Invoke-Json POST "$BaseUrl/api/v1/reservations" (@{ orderId = $order2; sku = $sku; quantity = 1 } | ConvertTo-Json)
if ($r3.StatusCode -ne 200) { throw "second reserve failed" }
$avail = Invoke-RestMethod "$BaseUrl/api/v1/products/$sku/availability"
if ($avail.available -ne 4) { throw "expected available 4" }
$messages = Invoke-RestMethod "$MailpitUrl/api/v1/messages"
if ($messages.total -ne 1) { throw "RF-A2 expected still 1 mail" }

$c1 = Invoke-Json POST "$BaseUrl/api/v1/reservations/$order1/confirm" $null
$c2 = Invoke-Json POST "$BaseUrl/api/v1/reservations/$order1/confirm" $null
if ($c1.StatusCode -ne 204 -or $c2.StatusCode -ne 204) { throw "confirm failed" }
$avail = Invoke-RestMethod "$BaseUrl/api/v1/products/$sku/availability"
if ($avail.available -ne 4) { throw "available moved after confirm" }

$flash = "FLASH-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
Invoke-Json PUT "$BaseUrl/api/v1/products/$flash" '{"category":"FLASH_SALE"}' | Out-Null
Invoke-Json POST "$BaseUrl/api/v1/products/$flash/stock" '{"quantity":10}' | Out-Null
$over = Invoke-Json POST "$BaseUrl/api/v1/reservations" (@{ orderId = $flashOrder1; sku = $flash; quantity = 3 } | ConvertTo-Json)
if ($over.StatusCode -ne 422) { throw "expected 422, got $($over.StatusCode)" }
$okFlash = Invoke-Json POST "$BaseUrl/api/v1/reservations" (@{ orderId = $flashOrder2; sku = $flash; quantity = 2 } | ConvertTo-Json)
if ($okFlash.StatusCode -ne 200) { throw "flash 2 should succeed" }

$short = Invoke-Json POST "$BaseUrl/api/v1/reservations" (@{ orderId = $overOrder; sku = $sku; quantity = 99 } | ConvertTo-Json)
if ($short.StatusCode -ne 409) { throw "expected 409 insufficient" }

if ($CheckRestart) {
    docker compose restart app | Out-Null
    Wait-Health
    $after = Invoke-RestMethod "$BaseUrl/api/v1/products/$sku/availability"
    if ($after.available -ne 4) { throw "availability not persisted after restart" }
}

Write-Host "SMOKE TEST OK"
exit 0
