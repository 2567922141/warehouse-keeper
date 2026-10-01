param(
  [string]$Out = "",
  [string]$OutDir = "",
  [string]$Seq = "",
  [int]$GapMs = 1200,
  [int]$AfterMs = 1500
)

# 默认输出目录：仓库根目录下的 run/screenshots（可用 -OutDir / -Out 覆盖）。
$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrEmpty($OutDir)) { $OutDir = Join-Path $repoRoot 'run\screenshots' }
if ([string]::IsNullOrEmpty($Out)) { $Out = Join-Path $OutDir 'ui.png' }

# Minecraft window capture + optional click/key sequence, all inside one process,
# so the game window keeps the focus (PrintWindow captures even if occluded).
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices;
public class WkUi {
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint type);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr SetActiveWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool f);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, UIntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }
  public static void Tap(byte vk) { byte sc = (byte)MapVirtualKey(vk, 0); keybd_event(vk, sc, 0, UIntPtr.Zero); System.Threading.Thread.Sleep(60); keybd_event(vk, sc, 2, UIntPtr.Zero); System.Threading.Thread.Sleep(60); }
  public static void TapShift(byte vk) { byte sc = (byte)MapVirtualKey(0x10, 0); keybd_event(0x10, sc, 0, UIntPtr.Zero); System.Threading.Thread.Sleep(40); Tap(vk); keybd_event(0x10, sc, 2, UIntPtr.Zero); System.Threading.Thread.Sleep(40); }
}'

$proc = Get-Process -ErrorAction SilentlyContinue |
  Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -eq 'java' -and $_.MainWindowTitle -like "*Minecraft*" } |
  Select-Object -First 1
if (-not $proc) { Write-Output "no Minecraft window"; exit 1 }
$h = $proc.MainWindowHandle
# Windows only lets the foreground process steal focus, so attach to the current
# foreground thread first (same trick as clientact.ps1), then re-assert.
[void][WkUi]::ShowWindow($h, 9)
# 顶到最前：SetForegroundWindow 常常失败，窗口被别的窗口盖住时真鼠标事件会打到别人身上
# （踩过两次：一次点到浏览器、一次把 gradle 控制台窗口关掉，客户端直接退出）
[void][WkUi]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x0001 -bor 0x0002 -bor 0x0040)
[void][WkUi]::BringWindowToTop($h)
[void][WkUi]::SetActiveWindow($h)
[void][WkUi]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 300
if ([WkUi]::GetForegroundWindow() -ne $h) {
  $fg = [WkUi]::GetForegroundWindow()
  [uint32]$fgPid = 0
  $fgThread = [WkUi]::GetWindowThreadProcessId($fg, [ref]$fgPid)
  $myThread = [WkUi]::GetCurrentThreadId()
  [void][WkUi]::AttachThreadInput($myThread, $fgThread, $true)
  [void][WkUi]::ShowWindow($h, 9)
  [void][WkUi]::SetForegroundWindow($h)
  Start-Sleep -Milliseconds 250
  [void][WkUi]::AttachThreadInput($myThread, $fgThread, $false)
}
Write-Output ("focused=" + ([WkUi]::GetForegroundWindow() -eq $h))
Start-Sleep -Milliseconds 300

# key name -> virtual key code
$vkMap = @{
  'esc' = 27; 'escape' = 27; 'enter' = 13; 'return' = 13; 'tab' = 9; 'space' = 32;
  'up' = 38; 'down' = 40; 'left' = 37; 'right' = 39; 'backspace' = 8;
  'a' = 65; 'b' = 66; 'c' = 67; 'd' = 68; 'e' = 69; 'f' = 70; 'g' = 71; 'h' = 72;
  'i' = 73; 'j' = 74; 'k' = 75; 'l' = 76; 'm' = 77; 'n' = 78; 'o' = 79; 'p' = 80;
  'q' = 81; 'r' = 82; 's' = 83; 't' = 84; 'u' = 85; 'v' = 86; 'w' = 87; 'x' = 88;
  'y' = 89; 'z' = 90;
  '1' = 49; '2' = 50; '3' = 51; '4' = 52; '5' = 53; '6' = 54; '7' = 55; '8' = 56; '9' = 57; '0' = 48;
  'f1' = 112; 'f2' = 113; 'f3' = 114; 'f4' = 115; 'f5' = 116; 'f6' = 117;
  'f7' = 118; 'f8' = 119; 'f9' = 120; 'f10' = 121; 'f11' = 122; 'f12' = 123
}

function Save-Window([string]$path) {
  $r = New-Object WkUi+RECT
  [void][WkUi]::GetWindowRect($h, [ref]$r)
  $w = $r.Right - $r.Left; $ht = $r.Bottom - $r.Top
  $bmp = New-Object System.Drawing.Bitmap($w, $ht)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $dc = $g.GetHdc()
  [void][WkUi]::PrintWindow($h, $dc, 2)
  $g.ReleaseHdc($dc); $g.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Output ("cap -> " + $path + "  " + $w + "x" + $ht)
}

foreach ($step in $Seq.Split(";")) {
  $s = $step.Trim()
  if ($s -eq "") { continue }
  if ($s.StartsWith("cap:")) {
    Save-Window (Join-Path $OutDir ($s.Substring(4) + ".png"))
  }
  elseif ($s.StartsWith("sleep:")) {
    Start-Sleep -Milliseconds ([int]$s.Substring(6))
  }
  elseif ($s.StartsWith("size:")) {
    # 只改窗口尺寸（不移动、不抢焦点）：客户区 = 窗口 - 边框，所以传大一点
    # 注意：步骤分隔符是 ';'，且 -Seq 里不要用逗号（工具层转参时会被吃掉），所以这里用 'x' 分隔宽高
    $body = $s.Substring(5)
    $bits = $body -split 'x'
    if ($bits.Length -ge 2) {
      $w2 = [int]$bits[0]
      $h2 = [int]$bits[1]
      [void][WkUi]::SetWindowPos($h, [IntPtr]::Zero, 0, 0, $w2, $h2, 0x0004 -bor 0x0010)
      Write-Output ("resized to " + $w2 + "x" + $h2)
    } else {
      Write-Output ("size step bad: " + $body)
    }
  }
  elseif ($s.StartsWith("pclick:")) {
    # posted click: works even when the game window is not foreground
    $parts = $s.Substring(7).Split(",")
    $cx = [int]$parts[0]; $cy = [int]$parts[1]
    $pt = New-Object WkUi+POINT
    $pt.X = $cx; $pt.Y = $cy
    [void][WkUi]::ClientToScreen($h, [ref]$pt)
    [void][WkUi]::SetCursorPos($pt.X, $pt.Y)
    Start-Sleep -Milliseconds 250
    $lp = [IntPtr](($cy -shl 16) -bor ($cx -band 0xFFFF))
    [void][WkUi]::PostMessage($h, 0x0200, [UIntPtr]::Zero, $lp)
    Start-Sleep -Milliseconds 120
    [void][WkUi]::PostMessage($h, 0x0201, ([UIntPtr]::new(1)), $lp)
    Start-Sleep -Milliseconds 90
    [void][WkUi]::PostMessage($h, 0x0202, [UIntPtr]::Zero, $lp)
    Write-Output ("posted click client " + $cx + "," + $cy)
  }
  elseif ($s.StartsWith("click:") -or $s.StartsWith("rclick:")) {
    $rc = $s.StartsWith("rclick:")
    $parts = $s.Substring($(if ($rc) { 7 } else { 6 })).Split(",")
    $pt = New-Object WkUi+POINT
    $pt.X = [int]$parts[0]; $pt.Y = [int]$parts[1]
    [void][WkUi]::ClientToScreen($h, [ref]$pt)
    [void][WkUi]::SetCursorPos($pt.X, $pt.Y)
    Start-Sleep -Milliseconds 300
    [WkUi]::mouse_event($(if ($rc) { 0x0008 } else { 0x0002 }), 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 90
    [WkUi]::mouse_event($(if ($rc) { 0x0010 } else { 0x0004 }), 0, 0, 0, [UIntPtr]::Zero)
    Write-Output ("clicked client " + $parts[0] + "," + $parts[1] + " -> screen " + $pt.X + "," + $pt.Y)
  }
  elseif ($s.StartsWith("key:")) {
    $name = $s.Substring(4).Trim().ToLower()
    if ($vkMap.ContainsKey($name)) {
      [WkUi]::Tap([byte]$vkMap[$name])
      Write-Output ("key " + $name + " vk=" + $vkMap[$name])
    } else {
      Write-Output ("unknown key " + $name)
    }
  }
  elseif ($s.StartsWith("type:")) {
    $text = $s.Substring(5)
    foreach ($ch in $text.ToCharArray()) {
      $c = [string]$ch
      $shift = $false
      if ($c -cmatch '^[A-Z]$') { $shift = $true; $c = $c.ToLower() }
      if ($c -eq ':') { $shift = $true; $c = ';' }
      if ($c -eq '_') { $shift = $true; $c = '-' }
      if ($c -eq '?') { $shift = $true; $c = '/' }
      $code = -1
      if ($vkMap.ContainsKey($c)) { $code = $vkMap[$c] }
      elseif ($c -eq '.') { $code = 190 } elseif ($c -eq ',') { $code = 188 }
      elseif ($c -eq '/') { $code = 191 } elseif ($c -eq '-') { $code = 189 }
      elseif ($c -eq ';') { $code = 186 } elseif ($c -eq " ") { $code = 32 }
      if ($code -lt 0) { Write-Output ("skip char " + $c); continue }
      if ($shift) { [WkUi]::TapShift([byte]$code) } else { [WkUi]::Tap([byte]$code) }
    }
    Write-Output ("typed " + $text)
  }
  Start-Sleep -Milliseconds $GapMs
}
Start-Sleep -Milliseconds $AfterMs

$r = New-Object WkUi+RECT
[void][WkUi]::GetWindowRect($h, [ref]$r)
$w = $r.Right - $r.Left; $ht = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap($w, $ht)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $g.GetHdc()
$ok = [WkUi]::PrintWindow($h, $dc, 2)
$g.ReleaseHdc($dc); $g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output ("PrintWindow=" + $ok + " pid=" + $proc.Id + " -> " + $Out + "  " + $w + "x" + $ht)
