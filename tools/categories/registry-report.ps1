# Summarise tools/categories/registry-all.txt (output of audit.ps1 -DumpAll).
# ASCII-only on purpose: Windows PowerShell reads BOM-less .ps1 as ANSI.
# Usage: .\tools\categories\registry-report.ps1 [-Path .\tools\categories\registry-all.txt]
param(
    [string]$Path = "$PSScriptRoot\registry-all.txt",
    [switch]$ListOther
)

$txt = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
$rows = New-Object System.Collections.Generic.List[object]

# The RCON reply arrives as one long line per page: header glued to the first id, then
# "id|cat|layer" repeated with each layer glued to the next id. One regex over the whole
# text recovers every triple; the layer stops at its first Chinese word.
$re = [regex]'([a-z0-9_.\-]+:[a-z0-9_/.\-]+)\|([^|]+)\|(L[0-9.]+ [\u4e00-\u9fff]+)'
foreach ($mm in $re.Matches($txt)) {
    $rows.Add([pscustomobject]@{
            id    = $mm.Groups[1].Value
            cat   = $mm.Groups[2].Value
            layer = $mm.Groups[3].Value
        })
}

"total items: " + $rows.Count
"--- per category ---"
$rows | Group-Object cat | Sort-Object Count -Descending | ForEach-Object { "  {0,-10} {1}" -f $_.Name, $_.Count }
"--- per layer ---"
$rows | Group-Object layer | Sort-Object Count -Descending | ForEach-Object { "  {0,-14} {1}" -f $_.Name, $_.Count }

# "其他" written as code points: this file is read as ANSI by Windows PowerShell, so a
# literal Chinese string here would arrive as mojibake and silently match nothing.
$otherName = [string]([char]0x5176) + [string]([char]0x4ED6)
$other = $rows | Where-Object { $_.cat -eq $otherName }
$eggs = $other | Where-Object { $_.id -like '*_spawn_egg' }
"--- other ---"
"  total {0}, spawn eggs {1}, real misses {2}" -f $other.Count, $eggs.Count, ($other.Count - $eggs.Count)
if ($ListOther) {
    $other | Where-Object { $_.id -notlike '*_spawn_egg' } | Sort-Object id | ForEach-Object { "  " + $_.id + "  [" + $_.layer + "]" }
}
