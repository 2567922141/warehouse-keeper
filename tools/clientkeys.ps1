param(
  [string]$Keys = "b,f2",
  [string]$ShotDir = "",
  [int]$GapMs = 1800,
  [int]$AfterMs = 2500
)

# 默认截图目录：仓库根目录下的 run/screenshots（可用 -ShotDir 覆盖）。
if ([string]::IsNullOrEmpty($ShotDir)) { $ShotDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'run\screenshots' }

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WkKey {
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);
}
"@

$proc = Get-Process -ErrorAction SilentlyContinue |
  Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -eq 'java' -and $_.MainWindowTitle -like "*Minecraft*" } |
  Select-Object -First 1
if (-not $proc) { Write-Output "no Minecraft window"; exit 1 }
$h = $proc.MainWindowHandle
Write-Output ("window: pid=" + $proc.Id + " title=" + $proc.MainWindowTitle)

$before = @()
if (Test-Path $ShotDir) {
  $before = @(Get-ChildItem $ShotDir -Filter *.png -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
}

foreach ($raw in $Keys.Split(",")) {
  $k = $raw.Trim().ToLower()
  $vk = 0
  if ($k -eq "b") { $vk = 0x42 }
  elseif ($k -eq "f2") { $vk = 0x71 }
  elseif ($k -eq "esc") { $vk = 0x1B }
  elseif ($k -eq "e") { $vk = 0x45 }
  else { $vk = [int]$k }
  $scan = [WkKey]::MapVirtualKey([uint32]$vk, 0)
  $down = [IntPtr](1 -bor ([int]$scan -shl 16))
  $up = [IntPtr](1 -bor ([int]$scan -shl 16) -bor 0xC0000000)
  [WkKey]::PostMessage($h, 0x0100, [IntPtr]$vk, $down) | Out-Null
  Start-Sleep -Milliseconds 70
  [WkKey]::PostMessage($h, 0x0101, [IntPtr]$vk, $up) | Out-Null
  Write-Output ("posted vk=0x" + $vk.ToString("X2") + " scan=" + $scan)
  Start-Sleep -Milliseconds $GapMs
}

Start-Sleep -Milliseconds $AfterMs

$new = $null
if (Test-Path $ShotDir) {
  $new = Get-ChildItem $ShotDir -Filter *.png -ErrorAction SilentlyContinue |
    Where-Object { $before -notcontains $_.Name } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
}
if ($new) {
  Write-Output ("new screenshot: " + $new.FullName + "  " + $new.Length + " bytes")
} else {
  Write-Output "no new screenshot appeared"
  $latest = Get-ChildItem $ShotDir -Filter *.png -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($latest) { Write-Output ("latest existing: " + $latest.FullName) }
}
