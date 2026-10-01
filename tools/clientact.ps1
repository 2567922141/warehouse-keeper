param(
  [string]$Click = "",
  [string]$Keys = "",
  [string]$Type = "",
  [string]$Seq = "",
  [int]$GapMs = 1200,
  [string]$ShotDir = "",
  [int]$AfterMs = 2000
)

# 默认截图目录：仓库根目录下的 run/screenshots（可用 -ShotDir 覆盖）。
if ([string]::IsNullOrEmpty($ShotDir)) { $ShotDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'run\screenshots' }

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WkAct {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool f);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr SetFocus(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint type);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }
  [DllImport("user32.dll")] public static extern short VkKeyScan(char ch);
}
"@

function Get-GameWindow {
  return Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -eq 'java' -and $_.MainWindowTitle -like "*Minecraft*" } |
    Select-Object -First 1
}

$proc = Get-GameWindow
if (-not $proc) { Write-Output "no Minecraft window"; exit 1 }
$h = $proc.MainWindowHandle
Write-Output ("window: pid=" + $proc.Id)

function Send-KeyCode([int]$vk) {
  $sc = [byte][WkAct]::MapVirtualKey([uint32]$vk, 0)
  [WkAct]::keybd_event([byte]$vk, $sc, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 40
  [WkAct]::keybd_event([byte]$vk, $sc, 2, [UIntPtr]::Zero)
}

function Send-Text([string]$txt) {
  foreach ($ch in $txt.ToCharArray()) {
    $ks = [WkAct]::VkKeyScan($ch)
    if ($ks -eq -1) { continue }
    $key = $ks -band 0xFF
    $shift = ((($ks -shr 8) -band 1) -eq 1)
    if ($shift) {
      [WkAct]::keybd_event(0x10, [byte][WkAct]::MapVirtualKey(0x10, 0), 0, [UIntPtr]::Zero)
      Start-Sleep -Milliseconds 20
    }
    Send-KeyCode $key
    if ($shift) { [WkAct]::keybd_event(0x10, [byte][WkAct]::MapVirtualKey(0x10, 0), 2, [UIntPtr]::Zero) }
    Start-Sleep -Milliseconds 30
  }
}

# 强制抢焦点
Add-Type -AssemblyName System.Windows.Forms
$fg = [WkAct]::GetForegroundWindow()
$myThread = [WkAct]::GetCurrentThreadId()
$fgPid = 0
$fgThread = $myThread
if ($fg -ne [IntPtr]::Zero) { $fgThread = [WkAct]::GetWindowThreadProcessId($fg, [ref]$fgPid) }
[WkAct]::ShowWindow($h, 9) | Out-Null
[WkAct]::AttachThreadInput($myThread, $fgThread, $true) | Out-Null
[WkAct]::BringWindowToTop($h) | Out-Null
[WkAct]::SetForegroundWindow($h) | Out-Null
[WkAct]::SetFocus($h) | Out-Null
[WkAct]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null
Start-Sleep -Milliseconds 700

$before = @()
if (Test-Path $ShotDir) {
  $before = @(Get-ChildItem $ShotDir -Filter *.png -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
}

if ($Seq -ne "") {
  foreach ($step in $Seq.Split(";")) {
    $s = $step.Trim()
    if ($s -eq "") { continue }
    if ($s.StartsWith("click:")) {
      $parts = $s.Substring(6).Split(",")
      $cx = [int]$parts[0]
      $cy = [int]$parts[1]
      $pt = New-Object WkAct+POINT
      $pt.X = $cx
      $pt.Y = $cy
      [WkAct]::ClientToScreen($h, [ref]$pt) | Out-Null
      [WkAct]::SetCursorPos($pt.X, $pt.Y) | Out-Null
      Start-Sleep -Milliseconds 350
      [WkAct]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
      Start-Sleep -Milliseconds 90
      [WkAct]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
      Write-Output ("clicked client " + $cx + "," + $cy + " -> screen " + $pt.X + "," + $pt.Y)
    }
    elseif ($s.StartsWith("rclick:")) {
      $parts = $s.Substring(7).Split(",")
      $cx = [int]$parts[0]
      $cy = [int]$parts[1]
      $pt = New-Object WkAct+POINT
      $pt.X = $cx
      $pt.Y = $cy
      [WkAct]::ClientToScreen($h, [ref]$pt) | Out-Null
      [WkAct]::SetCursorPos($pt.X, $pt.Y) | Out-Null
      Start-Sleep -Milliseconds 350
      [WkAct]::mouse_event(0x0008, 0, 0, 0, [UIntPtr]::Zero)
      Start-Sleep -Milliseconds 90
      [WkAct]::mouse_event(0x0010, 0, 0, 0, [UIntPtr]::Zero)
      Write-Output ("right-clicked client " + $cx + "," + $cy + " -> screen " + $pt.X + "," + $pt.Y)
    }
    elseif ($s.StartsWith("look:")) {
      $parts = $s.Substring(5).Split(",")
      $dx = [int]$parts[0]
      $dy = [int]$parts[1]
      [WkAct]::mouse_event(0x0001, $dx, $dy, 0, [UIntPtr]::Zero)
      Write-Output ("looked " + $dx + "," + $dy)
    }
    elseif ($s.StartsWith("key:")) {
      $k = $s.Substring(4).Trim().ToLower()
      $vk = 0
      if ($k -eq "b") { $vk = 0x42 }
      elseif ($k -eq "f2") { $vk = 0x71 }
      elseif ($k -eq "esc") { $vk = 0x1B }
      elseif ($k -eq "t") { $vk = 0x54 }
      elseif ($k -eq "q") { $vk = 0x51 }
      else { $vk = [int]$k }
      $scanByte = [byte][WkAct]::MapVirtualKey([uint32]$vk, 0)
      [WkAct]::keybd_event([byte]$vk, $scanByte, 0, [UIntPtr]::Zero)
      Start-Sleep -Milliseconds 60
      [WkAct]::keybd_event([byte]$vk, $scanByte, 2, [UIntPtr]::Zero)
      Write-Output ("pressed vk=0x" + $vk.ToString("X2"))
    }
    elseif ($s.StartsWith("type:")) {
      $txt = $s.Substring(5)
      $sendEnter = $false
      if ($txt.EndsWith("{ENTER}")) { $txt = $txt.Substring(0, $txt.Length - 7); $sendEnter = $true }
      Send-Text $txt
      if ($sendEnter) { Send-KeyCode 0x0D }
      Write-Output ("typed " + $txt)
    }
    Start-Sleep -Milliseconds $GapMs
  }
}

if ($Click -ne "") {
  foreach ($pair in $Click.Split(";")) {
    $parts = $pair.Trim().Split(",")
    $cx = [int]$parts[0]
    $cy = [int]$parts[1]
    $pt = New-Object WkAct+POINT
    $pt.X = $cx
    $pt.Y = $cy
    [WkAct]::ClientToScreen($h, [ref]$pt) | Out-Null
    [WkAct]::SetCursorPos($pt.X, $pt.Y) | Out-Null
    Start-Sleep -Milliseconds 350
    [WkAct]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 90
    [WkAct]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    Write-Output ("clicked client " + $cx + "," + $cy + " -> screen " + $pt.X + "," + $pt.Y)
    Start-Sleep -Milliseconds $GapMs
  }
}

if ($Keys -ne "") {
  foreach ($raw in $Keys.Split(",")) {
    $k = $raw.Trim().ToLower()
    $vk = 0
    if ($k -eq "b") { $vk = 0x42 }
    elseif ($k -eq "f2") { $vk = 0x71 }
    elseif ($k -eq "esc") { $vk = 0x1B }
    elseif ($k -eq "t") { $vk = 0x54 }
    else { $vk = [int]$k }
    $scan = [byte][WkAct]::MapVirtualKey([uint32]$vk, 0)
    [WkAct]::keybd_event([byte]$vk, $scan, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [WkAct]::keybd_event([byte]$vk, $scan, 2, [UIntPtr]::Zero)
    Write-Output ("pressed vk=0x" + $vk.ToString("X2"))
    Start-Sleep -Milliseconds $GapMs
  }
}

# 再拍一张
$scanF2 = [byte][WkAct]::MapVirtualKey(0x71, 0)
[WkAct]::keybd_event(0x71, $scanF2, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[WkAct]::keybd_event(0x71, $scanF2, 2, [UIntPtr]::Zero)
Start-Sleep -Milliseconds $AfterMs

$new = $null
if (Test-Path $ShotDir) {
  $new = Get-ChildItem $ShotDir -Filter *.png -ErrorAction SilentlyContinue |
    Where-Object { $before -notcontains $_.Name } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
if ($new) { Write-Output ("new screenshot: " + $new.FullName) } else { Write-Output "no new screenshot" }
