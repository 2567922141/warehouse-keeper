# Diff two audit outputs: the old-implementation baseline (id|category) against a fresh
# audit run (id|category|layer|reg=N). ASCII-only: Windows PowerShell reads BOM-less .ps1
# as ANSI, so no Chinese literals are used anywhere in this file.
# Usage: .\tools\categories\diff.ps1 [-Old old-corpus.txt] [-New corpus-v4.txt] [-Out diff.txt]
param(
    [string]$Old = "$PSScriptRoot\old-corpus.txt",
    [string]$New = "$PSScriptRoot\corpus-v4.txt",
    [string]$Out = "$PSScriptRoot\diff-v4.txt"
)

$oldLines = [System.IO.File]::ReadAllLines($Old, [System.Text.Encoding]::UTF8) | Where-Object { $_ -match '\|' }
$newLines = [System.IO.File]::ReadAllLines($New, [System.Text.Encoding]::UTF8) | Where-Object { $_ -match '\|' }

$oldMap = @{}
foreach ($l in $oldLines) {
    $p = $l.Split('|')
    $oldMap[$p[0]] = $p[1]
}

$rows = New-Object System.Collections.Generic.List[object]
$changed = 0; $same = 0; $changedReal = 0; $changedGhost = 0; $missing = 0
foreach ($l in $newLines) {
    $p = $l.Split('|')
    $id = $p[0]; $cat = $p[1]
    $layer = if ($p.Count -gt 2) { $p[2] } else { '' }
    $reg = if ($p.Count -gt 3 -and $p[3] -match 'reg=(\d)') { $Matches[1] } else { '?' }
    if (-not $oldMap.ContainsKey($id)) { $missing++; continue }
    if ($oldMap[$id] -ne $cat) {
        $changed++
        if ($reg -eq '1') { $changedReal++ } else { $changedGhost++ }
        $rows.Add([pscustomobject]@{ id = $id; line = "$id|$($oldMap[$id])->$cat|$layer|reg=$reg" })
    } else { $same++ }
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($Out, [string[]]($rows | ForEach-Object { $_.line }), $utf8)

"old lines: $($oldLines.Count), new lines: $($newLines.Count)"
"unchanged: $same, changed: $changed (real reg=1: $changedReal, not-in-registry reg=0: $changedGhost), old-only: $missing"
"written: $Out"
