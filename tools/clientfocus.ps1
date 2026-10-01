param(
  [string]$Keys = "esc,b,f2",
  [string]$ShotDir = "",
  [int]$GapMs = 1500,
  [int]$AfterMs = 2500
)

# 默认截图目录：仓库根目录下的 run/screenshots（可用 -ShotDir 覆盖）。
if ([string]::IsNullOrEmpty($ShotDir)) { $ShotDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'run\screenshots' }

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WkFocus {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern IntPtr SetFocus(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);
}
"@

$proc = Get-Process -ErrorAction SilentlyContinue |
  Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -eq 'java' -and $_.MainWindowTitle -like "*Minecraft*" } |
  Select-Object -First 1
if (-not $proc) { Write-Output "no Minecraft window"; exit 1 }
$h = $proc.MainWindowHandle
Write-Output ("window: pid=" + $proc.Id + " title=" + $proc.MainWindowTitle)

$fg = [WkFocus]::GetForegroundWindow()
$fgPid = 0
$fgThread = [WkFocus]::GetWindowThreadProcessId($fg, [ref]$fgPid)
$myThread = [WkFocus]::GetCurrentThreadId()
Write-Output ("foreground was pid=" + $fgPid + "; attaching " + $myThread + " -> " + $fgThread)

[WkFocus]::ShowWindow($h, 9) | Out-Null
[WkFocus]::AttachThreadInput($myThread, $fgThread, $true) | Out-Null
[WkFocus]::BringWindowToTop($h) | Out-Null
[WkFocus]::SetForegroundWindow($h) | Out-Null
[WkFocus]::SetFocus($h) | Out-Null
[WkFocus]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null
Start-Sleep -Milliseconds 900
Write-Output ("foreground now pid=" + [WkFocus]::GetWindowThreadProcessId([WkFocus]::GetForegroundWindow(), [ref]$fgPid))

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
  elseif ($k -eq "t") { $vk = 0x54 }
  else { $vk = [int]$k }
  $scan = [byte][WkFocus]::MapVirtualKey([uint32]$vk, 0)
  [WkFocus]::keybd_event([byte]$vk, $scan, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 60
  [WkFocus]::keybd_event([byte]$vk, $scan, 2, [UIntPtr]::Zero)
  Write-Output ("pressed vk=0x" + $vk.ToString("X2"))
  Start-Sleep -Milliseconds $GapMs
}

Start-Sleep -Milliseconds $AfterMs

$new = $null
if (Test-Path $ShotDir) {
  $new = Get-ChildItem $ShotDir -Filter *.png -ErrorAction SilentlyContinue |
    Where-Object { $before -notcontains $_.Name } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
if ($new) { Write-Output ("new screenshot: " + $new.FullName) } else { Write-Output "no new screenshot appeared" }
