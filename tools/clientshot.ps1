param(
  [string]$Key = "b",
  [string]$Out = "$env:TEMP\wk_client.png",
  [int]$WaitMs = 9000
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WkWin {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
"@

$proc = Get-Process -ErrorAction SilentlyContinue |
  Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -eq 'java' -and $_.MainWindowTitle -like "*Minecraft*" } |
  Select-Object -First 1
if (-not $proc) {
  Write-Output "no Minecraft window found"
  exit 1
}
Write-Output ("window: pid=" + $proc.Id + " title=" + $proc.MainWindowTitle)

$h = $proc.MainWindowHandle
$shell = New-Object -ComObject WScript.Shell
$shell.AppActivate($proc.Id) | Out-Null
Start-Sleep -Milliseconds 600
[WkWin]::ShowWindow($h, 9) | Out-Null
[WkWin]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 900

if ($Key -ne "-") {
  [System.Windows.Forms.SendKeys]::SendWait($Key)
  Write-Output ("sent key: " + $Key)
}

Start-Sleep -Milliseconds $WaitMs

$r = New-Object WkWin+RECT
[WkWin]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.Right - $r.Left
$ht = $r.Bottom - $r.Top
if ($w -le 0 -or $ht -le 0) {
  Write-Output "bad window rect"
  exit 1
}
$bmp = New-Object System.Drawing.Bitmap($w, $ht)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size($w, $ht)))
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output ("saved " + $Out + "  " + $w + "x" + $ht)
