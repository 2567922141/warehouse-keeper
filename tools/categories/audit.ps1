# Batch category audit over RCON.
#
# ASCII-only on purpose: Windows PowerShell reads a BOM-less .ps1 as ANSI, so any
# non-ASCII literal here would be mangled. Ids and category names (Chinese) are
# only ever read from UTF-8 files or received from the server, never written as
# literals in this file.
#
# Usage:
#   .\audit.ps1 -Ids tools\categories\corpus.txt -Out out\corpus-result.txt
#   .\audit.ps1 -Ids tools\categories\golden.txt -Out out\golden-result.txt
#     (a line "id|expectedCategory" is checked against the server verdict;
#      a line with only "id" is just dumped)
#   .\audit.ps1 -DumpAll -Out out\registry-dump.txt
#     (pages "warehouse categories dumpall <p>" until a page has no data lines)
#
# One TCP connection for the whole run, one command -> one response, no sleep:
# the per-item reply is a single short line, so it never gets split across
# packets (which is what rcon.ps1 gets wrong for long command output).
param(
    [string]$Ids = "",
    [string]$Out = "",
    [switch]$DumpAll,
    [string]$Password = $env:RCON_PASSWORD,
    [int]$Port = 25575,
    [string]$RconHost = "127.0.0.1",
    [int]$MaxPages = 400
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrEmpty($Password)) {
    throw 'RCON password not set: pass -Password or set the RCON_PASSWORD environment variable.'
}

$client = New-Object System.Net.Sockets.TcpClient($RconHost, $Port)
$client.ReceiveTimeout = 20000
$stream = $client.GetStream()

function Send-Packet([int]$packetId, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $len = 4 + 4 + $bodyBytes.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int]$len)
    $bw.Write([int]$packetId)
    $bw.Write([int]$type)
    $bw.Write($bodyBytes)
    $bw.Write([byte]0)
    $bw.Write([byte]0)
    $bw.Flush()
    $bytes = $ms.ToArray()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()
}

function Read-Packet {
    $lenBuf = New-Object byte[] 4
    $got = 0
    while ($got -lt 4) {
        $n = $stream.Read($lenBuf, $got, 4 - $got)
        if ($n -le 0) { return $null }
        $got += $n
    }
    $len = [BitConverter]::ToInt32($lenBuf, 0)
    if ($len -lt 10 -or $len -gt 100000) { return $null }
    $buf = New-Object byte[] $len
    $got = 0
    while ($got -lt $len) {
        $n = $stream.Read($buf, $got, $len - $got)
        if ($n -le 0) { break }
        $got += $n
    }
    return [System.Text.Encoding]::UTF8.GetString($buf, 8, $len - 10)
}

Send-Packet 1 3 $Password
$auth = Read-Packet
if ($auth -eq $null) { Write-Output "!! RCON auth failed (no reply)"; $client.Close(); exit 1 }
if ($auth.Length -gt 0) { Write-Output ("!! RCON auth rejected: " + $auth); $client.Close(); exit 1 }

$packetId = 2
function Ask([string]$command) {
    $script:packetId++
    Send-Packet $script:packetId 2 $command
    return (Read-Packet)
}

# Parse one "<id> -> <category> [<layer> <evidence>] reg=0|1" line.
# Returns @{ id; category; evidence; reg } or $null. No non-ASCII literal is used:
# the arrow is skipped by splitting on '[' and on whitespace.
function Parse-Verdict([string]$line) {
    $m = [regex]::Match($line, '\[(.+?)\]\s+reg=(\d)')
    if (-not $m.Success) { return $null }
    $head = $line.Substring(0, $line.IndexOf('[')).Trim()
    $tokens = $head -split '\s+'
    $cat = $tokens[$tokens.Length - 1]
    $idTokens = @()
    for ($i = 0; $i -lt $tokens.Length - 1; $i++) {
        if ($tokens[$i] -eq '' ) { continue }
        $idTokens += $tokens[$i]
    }
    $itemId = $idTokens[0]
    return @{ id = $itemId; category = $cat; evidence = $m.Groups[1].Value; reg = [int]$m.Groups[2].Value }
}

$results = New-Object System.Collections.Generic.List[string]
$hist = @{}
$regZero = New-Object System.Collections.Generic.List[string]

if ($DumpAll) {
    for ($page = 1; $page -le $MaxPages; $page++) {
        $resp = Ask ("warehouse categories dumpall " + $page)
        if ($resp -eq $null) { Write-Output ("!! no reply for page " + $page); break }
        $data = 0
        foreach ($line in ($resp -split "`n")) {
            $t = $line.Trim()
            if ($t.Contains("|")) {
                $results.Add($t)
                $data++
                $parts = $t -split '\|'
                if ($parts.Length -ge 2) {
                    $c = $parts[1]
                    if ($hist.ContainsKey($c)) { $hist[$c]++ } else { $hist[$c] = 1 }
                }
            }
        }
        if ($data -eq 0) { Write-Output ("pages done, last empty page = " + $page); break }
        if ($page % 20 -eq 0) { Write-Output ("  ... page " + $page + ", lines " + $results.Count) }
    }
} else {
    if ($Ids -eq "") { Write-Output "!! -Ids is required (or use -DumpAll)"; $client.Close(); exit 1 }
    $expect = @{}
    # NOTE: PowerShell variables are case-insensitive - never name a local $ids here,
    # it would clobber the $Ids parameter.
    $idList = New-Object System.Collections.Generic.List[string]
    foreach ($raw in (Get-Content -LiteralPath $Ids -Encoding UTF8)) {
        $t = $raw.Trim()
        if ($t -eq "" -or $t.StartsWith("#")) { continue }
        $parts = $t -split '\|'
        $itemId = $parts[0].Trim()
        if ($itemId -eq "") { continue }
        $idList.Add($itemId)
        if ($parts.Length -ge 2 -and $parts[1].Trim() -ne "") { $expect[$itemId] = $parts[1].Trim() }
    }
    Write-Output ("ids: " + $idList.Count + ", with expectation: " + $expect.Count)
    $n = 0
    foreach ($itemId in $idList) {
        $resp = Ask ("warehouse categories of " + $itemId)
        $n++
        $v = $null
        if ($resp -ne $null) {
            foreach ($line in ($resp -split "`n")) {
                $v = Parse-Verdict $line
                if ($v -ne $null) { break }
            }
        }
        if ($v -eq $null) {
            $results.Add($itemId + "|?|?|?")
            Write-Output ("!! unparsable reply for " + $itemId)
            continue
        }
        $results.Add($v.id + "|" + $v.category + "|" + $v.evidence + "|reg=" + $v.reg)
        if ($v.category -ne "") { if ($hist.ContainsKey($v.category)) { $hist[$v.category]++ } else { $hist[$v.category] = 1 } }
        if ($v.reg -eq 0) { $regZero.Add($v.id) }
        if ($n % 100 -eq 0) { Write-Output ("  ... " + $n + "/" + $ids.Count) }
    }
}

$client.Close()

Write-Output ""
if ($expect.Count -gt 0) {
    $bad = New-Object System.Collections.Generic.List[string]
    foreach ($r in $results) {
        $parts = $r -split '\|'
        $itemId = $parts[0]
        $cat = $parts[1]
        if ($expect.ContainsKey($itemId) -and $expect[$itemId] -ne $cat) {
            $bad.Add($itemId + "  expected=" + $expect[$itemId] + "  got=" + $cat)
        }
    }
    Write-Output ("===== golden: " + ($expect.Count - $bad.Count) + "/" + $expect.Count + " passed =====")
    foreach ($b in $bad) { Write-Output ("  MISMATCH " + $b) }
}

Write-Output "===== per-category ====="
foreach ($k in ($hist.Keys | Sort-Object)) { Write-Output ("  " + $k + " : " + $hist[$k]) }
Write-Output ("total lines: " + $results.Count)
if ($regZero.Count -gt 0) {
    Write-Output ("NOT IN REGISTRY (" + $regZero.Count + "): " + ($regZero -join ", "))
}

if ($Out -ne "") {
    $full = [System.IO.Path]::GetFullPath($Out)
    $dir = [System.IO.Path]::GetDirectoryName($full)
    if ($dir -ne "" -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    [System.IO.File]::WriteAllLines($full, $results, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output ("written: " + $full)
}
