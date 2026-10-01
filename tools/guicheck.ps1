# 校验「仓库管理」界面（B 面板）的边框有没有越界。
#
# 原理：面板/列表框/信息框的边框颜色固定（0xFF3D4757 面板、0xFF2A3342 列表框与信息框），
# 把截图里所有该颜色的像素找出来，按 WarehouseScreen.init() 的布局公式算出每一圈边框
# 「应该」在哪，再检查有没有像素跑到预期之外。
#
# 用法： pwsh -File tools\guicheck.ps1 -Image run\screenshots\xxx.png -WinW 1280 -WinH 720
param(
    [Parameter(Mandatory = $true)][string]$Image,
    [Parameter(Mandatory = $true)][int]$WinW,
    [Parameter(Mandatory = $true)][int]$WinH,
    [int]$Tol = 1
)

Add-Type -AssemblyName System.Drawing

$path = (Resolve-Path $Image).Path
$bmp = [System.Drawing.Bitmap]::FromFile($path)
$W = $bmp.Width
$H = $bmp.Height
$rect = New-Object System.Drawing.Rectangle 0, 0, $W, $H
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$bytes = New-Object byte[] ($stride * $H)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$bmp.UnlockBits($data)
$bmp.Dispose()

$PANEL_B = 0x57; $PANEL_G = 0x47; $PANEL_R = 0x3D
$BOX_B = 0x42; $BOX_G = 0x33; $BOX_R = 0x2A
$LIST_W = 190   # 必须与 WarehouseScreen.LIST_W 一致

$panelPx = New-Object System.Collections.Generic.List[object]
$boxPx = New-Object System.Collections.Generic.List[object]
for ($y = 0; $y -lt $H; $y++) {
    $row = $y * $stride
    for ($x = 0; $x -lt $W; $x++) {
        $o = $row + $x * 4
        $b = $bytes[$o]; $g = $bytes[$o + 1]; $r = $bytes[$o + 2]
        if ($b -eq $PANEL_B -and $g -eq $PANEL_G -and $r -eq $PANEL_R) { $panelPx.Add(@($x, $y)) }
        elseif ($b -eq $BOX_B -and $g -eq $BOX_G -and $r -eq $BOX_R) { $boxPx.Add(@($x, $y)) }
    }
}

if ($panelPx.Count -eq 0) { Write-Output "FAIL: 截图里没有面板边框颜色 0xFF3D4757 -- 界面是不是没开着？"; exit 2 }

$minX = [int]::MaxValue; $minY = [int]::MaxValue; $maxX = -1; $maxY = -1
foreach ($p in $panelPx) {
    if ($p[0] -lt $minX) { $minX = $p[0] }
    if ($p[0] -gt $maxX) { $maxX = $p[0] }
    if ($p[1] -lt $minY) { $minY = $p[1] }
    if ($p[1] -gt $maxY) { $maxY = $p[1] }
}
Write-Output ("检测到面板边框像素 {0} 个，bbox = ({1},{2}) .. ({3},{4})" -f $panelPx.Count, $minX, $minY, $maxX, $maxY)
Write-Output ("列表框/信息框边框像素 {0} 个" -f $boxPx.Count)

# ---- 对每个 GUI scale 试算布局，挑出与实测 bbox 吻合的那个 ----
$bestScale = 0
$bPanX0 = 0; $bPanY0 = 0; $bPanX1 = 0; $bPanY1 = 0
$bListX0 = 0; $bListY0 = 0; $bListX1 = 0; $bListY1 = 0
$bInfoX0 = 0; $bInfoY0 = 0; $bInfoX1 = 0; $bInfoY1 = 0
$bInfoH = 0
for ($s = 1; $s -le 6; $s++) {
    # MC 的逻辑分辨率是 ceil(物理 / GUI scale)（注意不是 floor：729/2 = 365）
    $lw = [int][math]::Ceiling($WinW / $s)
    $lh = [int][math]::Ceiling($WinH / $s)
    $pwL = [int][math]::Min(660, $lw - 16)
    $phL = [int][math]::Min(360, $lh - 16)
    $pxL = [int](($lw - $pwL) / 2)
    $pyL = [int](($lh - $phL) / 2)
    $compact = $false
    if ($phL -lt 300) { $compact = $true }
    $lxL = $pxL + 12
    $lyOff = 56
    if ($compact) { $lyOff = 40 }
    $lyL = $pyL + $lyOff
    $statusPad = 42
    if ($compact) { $statusPad = 30 }
    $statusTop = $pyL + $phL - $statusPad
    $orderH = 18
    if ($compact) { $orderH = 16 }
    $orderY = $statusTop - 6 - $orderH
    $porterY2 = $orderY - 4 - $orderH      # admin = true（单人房主 / OP）
    $porterY1 = $porterY2 - 4 - $orderH
    $lhRow = $porterY1 - 6 - $lyL
    if ($lhRow -lt 40) { $lhRow = 40 }
    $listHL = [int]($lhRow * 0.40)
    if ($listHL -lt 36) { $listHL = 36 }
    # 搜索框：listH / searchH / infoH 三段（WarehouseScreen.init() 第 131-148 行）
    $infoFloor = 3 * 11 + 19
    $searchHL = 0
    if (($lhRow - $listHL - 14 - $infoFloor) -ge 20) { $searchHL = 20 }
    $infoYL = $lyL + $listHL + $searchHL + 14
    $infoHL = $lhRow - $listHL - $searchHL - 14
    $wantInfo = 3 * 11 + 19
    if ($infoHL -gt 0 -and $infoHL -lt $wantInfo) {
        $infoHL = [int][math]::Min($wantInfo, $lhRow - 36 - $searchHL - 14)
        $listHL = $lhRow - $searchHL - $infoHL - 14
        if ($listHL -lt 36) { $listHL = 36 }
        $infoYL = $lyL + $listHL + $searchHL + 14
    }
    if ($infoHL -lt 26) { $infoHL = 0 }
    # 搜索框本体（EditBox 自带一圈边框，颜色和列表框边框相同）
    $searchYL = $lyL + $listHL + 2

    $pX0 = $pxL * $s; $pY0 = $pyL * $s
    $pX1 = ($pxL + $pwL) * $s; $pY1 = ($pyL + $phL) * $s
    $d = 0
    $d = [math]::Max($d, [math]::Abs($pX0 - $minX))
    $d = [math]::Max($d, [math]::Abs($pY0 - $minY))
    $d = [math]::Max($d, [math]::Abs(($pX1 - 1) - $maxX))
    $d = [math]::Max($d, [math]::Abs(($pY1 - 1) - $maxY))
    Write-Output ("  scale=$s 预期面板 = ($pX0,$pY0)..($($pX1 - 1),$($pY1 - 1))  最大偏差 $d")
    if ($d -le $Tol -and $bestScale -eq 0) {
        $bestScale = $s
        $bPanX0 = $pX0; $bPanY0 = $pY0; $bPanX1 = $pX1; $bPanY1 = $pY1
        $bListX0 = $lxL * $s; $bListY0 = $lyL * $s; $bListX1 = ($lxL + $LIST_W) * $s; $bListY1 = ($lyL + $listHL) * $s
        $bInfoX0 = $lxL * $s; $bInfoY0 = $infoYL * $s; $bInfoX1 = ($lxL + $LIST_W) * $s; $bInfoY1 = ($infoYL + $infoHL) * $s
        $bInfoH = $infoHL
        $bSearchH = $searchHL
        $bSearchX0 = $lxL * $s; $bSearchY0 = $searchYL * $s
        $bSearchX1 = ($lxL + $LIST_W) * $s; $bSearchY1 = ($searchYL + $searchHL - 4) * $s
    }
}
if ($bestScale -eq 0) { Write-Output "FAIL: 没有哪个 GUI scale 能让预期布局对上实测边框 -- 布局公式与实机不一致"; exit 3 }
Write-Output ("采用 GUI scale = $bestScale；预期 列表框 = ($bListX0,$bListY0)..($($bListX1 - 1),$($bListY1 - 1))，信息框 = ($bInfoX0,$bInfoY0)..($($bInfoX1 - 1),$($bInfoY1 - 1)) infoH=$bInfoH")

function Test-OnRing([int]$x, [int]$y, [int]$x0, [int]$y0, [int]$x1, [int]$y1, [int]$tol) {
    if ($x -lt ($x0 - $tol) -or $x -gt ($x1 + $tol) -or $y -lt ($y0 - $tol) -or $y -gt ($y1 + $tol)) { return $false }
    $nearV = ([math]::Abs($x - $x0) -le $tol) -or ([math]::Abs($x - ($x1 - 1)) -le $tol)
    $nearH = ([math]::Abs($y - $y0) -le $tol) -or ([math]::Abs($y - ($y1 - 1)) -le $tol)
    if ($nearV -or $nearH) { return $true }
    return $false
}

$bad = New-Object System.Collections.Generic.List[object]
foreach ($p in $panelPx) {
    if (-not (Test-OnRing $p[0] $p[1] $bPanX0 $bPanY0 $bPanX1 $bPanY1 $Tol)) { $bad.Add(@('panel', $p[0], $p[1])) }
}
# 列表框/信息框：层高公式与 init() 可能有 1~2 像素的取整差，这里给 Tol2 宽容度；
# 但原 bug（右边画到 lx + (lx + lw)）会偏出 100 像素以上，照样会被抓住。
$Tol2 = 4
foreach ($p in $boxPx) {
    $ok = Test-OnRing $p[0] $p[1] $bListX0 $bListY0 $bListX1 $bListY1 $Tol2
    if (-not $ok -and $bInfoH -gt 0) { $ok = Test-OnRing $p[0] $p[1] $bInfoX0 $bInfoY0 $bInfoX1 $bInfoY1 $Tol2 }
    # 搜索框（原版 EditBox 自带边框，颜色与列表框边框相同）
    if (-not $ok -and $bSearchH -gt 0) { $ok = Test-OnRing $p[0] $p[1] $bSearchX0 $bSearchY0 $bSearchX1 $bSearchY1 $Tol2 }
    if (-not $ok) { $bad.Add(@('box', $p[0], $p[1])) }
}

if ($bad.Count -eq 0) {
    Write-Output "PASS: 所有边框像素都落在它们自己的矩形边界上，没有越界。"
    exit 0
} else {
    Write-Output ("FAIL: {0} 个边框像素在自己的矩形之外（前 20 个）：" -f $bad.Count)
    $i = 0
    foreach ($b in $bad) { if ($i -lt 20) { Write-Output ("   {0} ({1}, {2})" -f $b[0], $b[1], $b[2]) }; $i++ }
    exit 1
}
