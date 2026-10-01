# 往 Minecraft 窗口里粘贴一段文字（Ctrl+V），用来在搜索框里输入中文。
# 中文请用 [char]0x.... 拼出来传进来，脚本文件本身保持纯 ASCII。
# 用法： pwsh -File tools\paste.ps1 -Text ([char]0x94BB + [char]0x77F3)
param(
    [Parameter(Mandatory = $true)][string]$Text,
    [int]$TargetPid = 0,
    [int]$AfterMs = 1500
)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WkPaste {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool f);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
}
"@

$proc = if ($TargetPid -ne 0) {
    Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
} else {
    Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "*Minecraft*" } |
        Select-Object -First 1
}
if (-not $proc) { throw "找不到 Minecraft 窗口" }
$hwnd = $proc.MainWindowHandle
$fg = [WkPaste]::GetForegroundWindow()
$t1 = [WkPaste]::GetWindowThreadProcessId($fg, [ref]([uint32]0))
$t2 = [WkPaste]::GetWindowThreadProcessId($hwnd, [ref]([uint32]0))
[WkPaste]::AttachThreadInput($t1, $t2, $true) | Out-Null
[WkPaste]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 400

Set-Clipboard -Value $Text
Start-Sleep -Milliseconds 250

$VK_CONTROL = 0x11
$VK_V = 0x56
[WkPaste]::keybd_event($VK_CONTROL, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[WkPaste]::keybd_event($VK_V, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 80
[WkPaste]::keybd_event($VK_V, 0, 2, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[WkPaste]::keybd_event($VK_CONTROL, 0, 2, [UIntPtr]::Zero)
Write-Output ("pasted " + $Text.Length + " chars into pid=" + $proc.Id)
Start-Sleep -Milliseconds $AfterMs
