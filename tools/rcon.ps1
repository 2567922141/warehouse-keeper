# Minimal RCON client (local smoke test only). ASCII-only on purpose:
# Windows PowerShell reads BOM-less .ps1 as ANSI, so non-ASCII here would break parsing.
# Usage: . .\tools\rcon.ps1 ; Invoke-Rcon -Commands @("list","warehouse status")
function Invoke-Rcon {
    param(
        [Parameter(Mandatory = $true)][string[]]$Commands,
        [string]$Password = $env:RCON_PASSWORD,
        [int]$Port = 25575,
        [string]$RconHost = "127.0.0.1",
        [int]$WaitMs = 300
    )

    if ([string]::IsNullOrEmpty($Password)) {
        throw 'RCON password not set: pass -Password or set the RCON_PASSWORD environment variable.'
    }

    $client = New-Object System.Net.Sockets.TcpClient($RconHost, $Port)
    $stream = $client.GetStream()

    function Send-Packet([int]$id, [int]$type, [string]$body) {
        # UTF-8 (not ASCII): commands may contain Chinese, e.g. a region direction alias.
        # Encoding.UTF8.GetBytes does NOT prepend a BOM.
        $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $len = 4 + 4 + $bodyBytes.Length + 2
        $ms = New-Object System.IO.MemoryStream
        $bw = New-Object System.IO.BinaryWriter($ms)
        $bw.Write([int]$len)
        $bw.Write([int]$id)
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

    # A single MC reply can be split across several packets (it chunks long output,
    # e.g. a big /warehouse stats). Read until the socket goes quiet.
    function Read-Response {
        $parts = New-Object System.Collections.Generic.List[string]
        $client.ReceiveTimeout = 600
        try {
            while ($true) {
                $p = Read-Packet
                if ($p -eq $null) { break }
                $parts.Add($p)
            }
        } catch {
            # timeout: no more packets for this reply
        }
        $client.ReceiveTimeout = 0
        return ($parts -join "`n")
    }

    Send-Packet 1 3 $Password
    $auth = Read-Packet
    if ($auth -eq $null) { Write-Output "!! RCON auth failed (no reply)"; $client.Close(); return }
    # NOTE: on success the server replies with an EMPTY body (length 10 packet).
    # A failed auth replies with id = -1 and a non-empty body.
    if ($auth.Length -gt 0) { Write-Output ("!! RCON auth rejected: " + $auth); $client.Close(); return }

    $id = 2
    foreach ($c in $Commands) {
        Send-Packet $id 2 $c
        Start-Sleep -Milliseconds $WaitMs
        $resp = Read-Response
        Write-Output ("> " + $c)
        if ($resp -eq $null -or $resp.Length -eq 0) { Write-Output "  (no reply)" } else { Write-Output $resp.Trim() }
        Write-Output ""
        $id++
    }

    $client.Close()
}
