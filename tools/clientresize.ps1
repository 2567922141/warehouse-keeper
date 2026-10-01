# 把开发客户端窗口改成指定尺寸，然后在窗口里按键、拍一张 F2 截图并打印截图尺寸。
# 用来验证「不同窗口尺寸 / 不同 GUI Scale 下 B 面板的边框是否仍然不越界」。
#
# 用法： pwsh -File tools\clientresize.ps1 -W 1024 -H 768 -Seq "key:esc;key:b"
param(
    [int]$Width = 1600,
    [int]$Height = 900,
    [string]$Seq = "key:esc;key:b",
    [int]$GapMs = 1600,
    [int]$AfterMs = 2500,
    [string]$ShotDir = ""
)

# 默认截图目录：仓库根目录下的 run/screenshots（可用 -ShotDir 覆盖）。
if ([string]::IsNullOrEmpty($ShotDir)) { $ShotDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'run\screenshots' }

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WkResize {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool f);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr SetFocus(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int t, bool repaint);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint type);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@

$proc = Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -eq 'java' -and $_.MainWindowTitle -like "*Minecraft*" } |
    Select-Object -First 1
if (-not $proc) { Write-Output "no Minecraft window"; exit 1 }
$hwnd = $proc.MainWindowHandle
Write-Output ("window: pid=" + $proc.Id)

$fg = [WkResize]::GetForegroundWindow()
$myThread = [WkResize]::GetCurrentThreadId()
$fgPid = 0
$fgThread = $myThread
if ($fg -ne [IntPtr]::Zero) { $fgThread = [WkResize]::GetWindowThreadProcessId($fg, [ref]$fgPid) }
[WkResize]::ShowWindow($hwnd, 9) | Out-Null
[WkResize]::AttachThreadInput($myThread, $fgThread, $true) | Out-Null
[WkResize]::BringWindowToTop($hwnd) | Out-Null
[WkResize]::SetForegroundWindow($hwnd) | Out-Null
[WkResize]::SetFocus($hwnd) | Out-Null
[WkResize]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null

# 先把面板关掉（esc），再改窗口大小，避免 resize 时界面正好停在旧尺寸上
function Send-Key([int]$vk) {
    $sc = [byte][WkResize]::MapVirtualKey([uint32]$vk, 0)
    [WkResize]::keybd_event([byte]$vk, $sc, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [WkResize]::keybd_event([byte]$vk, $sc, 2, [UIntPtr]::Zero)
}
Send-Key 0x1B   # esc
Start-Sleep -Milliseconds 900

[WkResize]::MoveWindow($hwnd, 40, 40, $Width, $Height, $true) | Out-Null
Start-Sleep -Milliseconds 2500
$r = New-Object WkResize+RECT
[WkResize]::GetClientRect($hwnd, [ref]$r) | Out-Null
Write-Output ("client area = " + ($r.R - $r.L) + " x " + ($r.B - $r.T))

$before = @()
if (Test-Path $ShotDir) {
    $before = @(Get-ChildItem $ShotDir -Filter *.png -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
}

foreach ($step in $Seq.Split(";")) {
    $s = $step.Trim()
    if ($s -eq "") { continue }
    if ($s.StartsWith("key:")) {
        $k = $s.Substring(4).Trim().ToLower()
        $vk = 0
        if ($k -eq "b") { $vk = 0x42 }
        elseif ($k -eq "esc") { $vk = 0x1B }
        elseif ($k -eq "f2") { $vk = 0x71 }
        else { $vk = [int]$k }
        Send-Key $vk
        Write-Output ("pressed vk=0x" + $vk.ToString("X2"))
    }
    Start-Sleep -Milliseconds $GapMs
}

Send-Key 0x71   # F2
Start-Sleep -Milliseconds $AfterMs

$new = Get-ChildItem $ShotDir -Filter *.png -ErrorAction SilentlyContinue |
    Where-Object { $before -notcontains $_.Name } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($new) {
    Add-Type -AssemblyName System.Drawing
    $bmp = [System.Drawing.Bitmap]::FromFile($new.FullName)
    Write-Output ("new screenshot: " + $new.FullName)
    Write-Output ("screenshot size = " + $bmp.Width + " x " + $bmp.Height)
    $bmp.Dispose()
} else {
    Write-Output "no new screenshot"
}
