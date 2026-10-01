# 校验「仓库管理」界面（B 面板）的边框有没有画到面板之外。
#
# 与旧的 guicheck.ps1 不同：这里不重算布局公式（布局已经改成响应式的，写死公式必过期），
# 只查一条不变量：面板边框色 0xFF3D4757 围出的矩形之外，不允许出现任何
# 「本模组用的边框色」（面板 0xFF3D4757 / 列表框信息框 0xFF2A3342 / 页签选中 0xFF4E8BD8）。
#
# 用法： pwsh -File tools\bordercheck.ps1 -Image run\screenshots\xxx.png [-Margin 8]
param(
    [Parameter(Mandatory = $true)][string]$Image,
    [int]$Margin = 8          # WarehouseScreen.MARGIN
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

$tracked = @{
    '3D4757' = @(0x57, 0x47, 0x3D)   # 面板边框
    '2A3342' = @(0x42, 0x33, 0x2A)   # 列表框 / 信息框 / 未选中页签
    '4E8BD8' = @(0xD8, 0x8B, 0x4E)   # 选中页签
}

$minX = [int]::MaxValue; $maxX = -1; $minY = [int]::MaxValue; $maxY = -1
$outside = New-Object System.Collections.Generic.List[string]
$counts = @{}
foreach ($k in $tracked.Keys) { $counts[$k] = 0 }

for ($y = 0; $y -lt $H; $y++) {
    $row = $y * $stride
    for ($x = 0; $x -lt $W; $x++) {
        $o = $row + $x * 4
        $b = $bytes[$o]; $g = $bytes[$o + 1]; $r = $bytes[$o + 2]
        $hex = '{0:X2}{1:X2}{2:X2}' -f $r, $g, $b
        if (-not $tracked.ContainsKey($hex)) { continue }
        $counts[$hex] = $counts[$hex] + 1
        if ($hex -eq '3D4757') {
            if ($x -lt $minX) { $minX = $x }; if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }; if ($y -gt $maxY) { $maxY = $y }
        }
    }
}

if ($maxX -lt 0) { Write-Output "FAIL: 截图里找不到面板边框色 0xFF3D4757（面板没打开？）"; exit 1 }

# 面板必须在 MARGIN*scale 的整数倍偏移上（scale = 面板左上角到屏幕边缘的距离 / MARGIN）
$scale = [int][Math]::Round($minX / [double]$Margin)
if ($scale -lt 1) { $scale = 1 }

for ($y = 0; $y -lt $H; $y++) {
    $row = $y * $stride
    for ($x = 0; $x -lt $W; $x++) {
        $o = $row + $x * 4
        $b = $bytes[$o]; $g = $bytes[$o + 1]; $r = $bytes[$o + 2]
        $hex = '{0:X2}{1:X2}{2:X2}' -f $r, $g, $b
        if (-not $tracked.ContainsKey($hex)) { continue }
        if ($x -lt $minX -or $x -gt $maxX -or $y -lt $minY -or $y -gt $maxY) {
            if ($outside.Count -lt 12) { $outside.Add("$hex @ ($x,$y)") }
        }
    }
}

$panelW = $maxX - $minX + 1
$panelH = $maxY - $minY + 1
Write-Output ("image={0}x{1}  panel=({2},{3})-({4},{5}) {6}x{7}px  guiScale={8}  逻辑 {9}x{10}" -f `
    $W, $H, $minX, $minY, $maxX, $maxY, $panelW, $panelH, $scale, ($panelW / $scale), ($panelH / $scale))
Write-Output ("pixels: 面板边 {0} / 框边 {1} / 页签 {2}" -f $counts['3D4757'], $counts['2A3342'], $counts['4E8BD8'])

if ($outside.Count -eq 0) {
    Write-Output "PASS: 所有边框像素都在面板矩形内，没有越界线。"
    exit 0
}
Write-Output ("FAIL: 有 {0}+ 个边框像素画到面板外，例如：" -f $outside.Count)
$outside | ForEach-Object { Write-Output ("  " + $_) }
exit 1
