<#
.SYNOPSIS
    扫描玩家可见文案里的口语词与感叹号，命中即失败。

.DESCRIPTION
    规则见 docs/文案风格.md。只看 src/main/java 下 .java 文件的**字符串字面量**：
    注释与日志不在此列。语音识别用的同义词典（VoiceText.java）属于数据，默认跳过。

.PARAMETER Path
    要扫描的仓库根目录；缺省为脚本所在仓库，以及同级的 warehouse-keeper-voice。

.PARAMETER List
    只列出命中，不改变退出码（用于人工先看清单）。

.EXAMPLE
    pwsh -File scripts/copycheck.ps1

.EXAMPLE
    pwsh -File scripts/copycheck.ps1 -Path D:\DS\warehouse-keeper-voice -List
#>
[CmdletBinding()]
param(
    [string[]] $Path = @(),
    [switch] $List
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

# 语气词：单独出现就口语
$particles = '呢吧哦啦哟呀嘛嘿哈咦诶哎'
# 口语短语：整词命中
$phrases = @('点一下', '点这里', '点它', '没法', '弄死', '稍等', '先别', '搞定', '凑合', '咱们', '一下下', '真的删掉')
# 数据文件：里面的词表不是给玩家看的文案
$skipFiles = @('VoiceText.java')

$literal = [regex]'"((?:[^"\\]|\\.)*)"'

function Get-Roots {
    if ($Path.Count -gt 0) {
        return $Path
    }
    $here = Split-Path -Parent $PSScriptRoot
    $roots = @($here)
    $voice = Join-Path (Split-Path -Parent $here) 'warehouse-keeper-voice'
    if (Test-Path -LiteralPath $voice) {
        $roots += $voice
    }
    return $roots
}

$hits = 0
foreach ($root in Get-Roots) {
    $src = Join-Path $root 'src/main/java'
    if (-not (Test-Path -LiteralPath $src)) {
        Write-Warning "跳过（没有 src/main/java）：$root"
        continue
    }
    foreach ($file in Get-ChildItem -LiteralPath $src -Recurse -Filter *.java -File) {
        if ($skipFiles -contains $file.Name) {
            continue
        }
        $lineNo = 0
        foreach ($line in [System.IO.File]::ReadLines($file.FullName, [System.Text.Encoding]::UTF8)) {
            $lineNo++
            if ($line.Contains('copycheck:ignore')) { continue }
            foreach ($m in $literal.Matches($line)) {
                $text = $m.Groups[1].Value
                $why = @()
                if ($text.IndexOf('！') -ge 0) {
                    $why += '！'
                }
                foreach ($c in $particles.ToCharArray()) {
                    if ($text.IndexOf($c) -ge 0) {
                        $why += [string]$c
                    }
                }
                foreach ($p in $phrases) {
                    if ($text.IndexOf($p, [System.StringComparison]::Ordinal) -ge 0) {
                        $why += $p
                    }
                }
                if ($why.Count -gt 0) {
                    $hits++
                    $rel = $file.FullName.Substring($root.Length).TrimStart('\', '/')
                    Write-Output ("{0}:{1}: {2}  ->  {3}" -f $rel, $lineNo, ($why -join ' '), $text)
                }
            }
        }
    }
}

if ($hits -gt 0) {
    Write-Output ("共 {0} 处口语/感叹号，规则见 docs/文案风格.md。" -f $hits)
    if (-not $List) {
        exit 1
    }
} else {
    Write-Output '文案检查通过：未发现口语词与感叹号。'
}
