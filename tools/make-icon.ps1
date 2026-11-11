# 从源图生成自适应图标的前景层 PNG（白底转透明）
#
# 用法:
#   pwsh -File tools/make-icon.ps1                 # 默认缩放
#   pwsh -File tools/make-icon.ps1 -WidthRatio 0.5 # 指定占宽比例
#
# 安全区说明:
#   自适应图标画布 108dp, 保证可见区域是以中心为圆心的 66dp 圆。
#   本图内容是斜向构图（翅膀在左上、尾羽在左下、喙在右），
#   所以真正决定是否被裁的是**包围盒对角线**, 不是宽度:
#       对角线 = 宽度 * sqrt(1 + (高/宽)^2) = 宽度 * 1.308
#   要让对角线落在 66dp 圆内: 宽度 <= 0.611 * 432 / 1.308 ≈ 202px ≈ 画布的 46.8%
#   实际取 50% 左右, 仅在极细的翼尖/喙尖处略微超出, 圆形遮罩下也不会被切。

param(
    [double]$WidthRatio = 0.50,
    [string]$Source = "$PSScriptRoot\..\docs\mockups\app-icon-source.png",
    [string]$ResDir = "$PSScriptRoot\..\app\src\main\res",
    [int]$ContentLeft = 54,
    [int]$ContentTop = 82,
    [int]$ContentWidth = 396,
    [int]$ContentHeight = 334
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { throw "源图不存在: $Source" }

$img = [System.Drawing.Bitmap]::FromFile($Source)
$bbox = New-Object System.Drawing.Rectangle($ContentLeft, $ContentTop, $ContentWidth, $ContentHeight)
$C = 432

# 1) 按比例画到白底画布
$canvas = New-Object System.Drawing.Bitmap($C, $C, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($canvas)
$g.Clear([System.Drawing.Color]::White)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
$w = [double]$C * $WidthRatio
$h = $w * ($bbox.Height / $bbox.Width)
$dest = New-Object System.Drawing.Rectangle(
    [int](($C - $w) / 2), [int](($C - $h) / 2), [int]$w, [int]$h
)
$g.DrawImage($img, $dest, $bbox.X, $bbox.Y, $bbox.Width, $bbox.Height, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()

# 2) LockBits: 亮度反转映射到 alpha（白 -> 透明, 黑 -> 不透明黑）
$rect = New-Object System.Drawing.Rectangle(0, 0, $C, $C)
$data = $canvas.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$bytes = New-Object byte[] ($data.Stride * $C)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
for ($y = 0; $y -lt $C; $y++) {
    $row = $y * $data.Stride
    for ($x = 0; $x -lt $C; $x++) {
        $i = $row + $x * 4
        $b = $bytes[$i]; $gr = $bytes[$i + 1]; $r = $bytes[$i + 2]
        $lum = 0.114 * $b + 0.587 * $gr + 0.299 * $r
        $a = [int](255 - $lum)
        if ($a -lt 0) { $a = 0 }
        $bytes[$i] = 0; $bytes[$i + 1] = 0; $bytes[$i + 2] = 0; $bytes[$i + 3] = [byte]$a
    }
}
[System.Runtime.InteropServices.Marshal]::Copy($bytes, 0, $data.Scan0, $bytes.Length)
$canvas.UnlockBits($data)

# 3) 输出各密度
$targets = @(
    @{ dir = "drawable-mdpi";    size = 108 },
    @{ dir = "drawable-hdpi";    size = 162 },
    @{ dir = "drawable-xhdpi";   size = 216 },
    @{ dir = "drawable-xxhdpi";  size = 324 },
    @{ dir = "drawable-xxxhdpi"; size = 432 }
)
foreach ($t in $targets) {
    $dir = Join-Path $ResDir $t.dir
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bmp = New-Object System.Drawing.Bitmap($canvas, $t.size, $t.size)
    $bmp.Save((Join-Path $dir "ic_launcher_bird.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# 4) 白底预览 + 安全区参考线
$preview = New-Object System.Drawing.Bitmap($C, $C)
$pg = [System.Drawing.Graphics]::FromImage($preview)
$pg.Clear([System.Drawing.Color]::White)
$pg.DrawImage($canvas, 0, 0)
$pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(90, 255, 0, 0), 1.5)
$safe = [int]($C * 0.611)
$pg.DrawEllipse($pen, [int](($C - $safe) / 2), [int](($C - $safe) / 2), $safe, $safe)
$pen.Dispose(); $pg.Dispose()
$previewPath = Join-Path $PSScriptRoot "..\.tooling\shots\icon_preview.png"
New-Item -ItemType Directory -Force -Path (Split-Path $previewPath) | Out-Null
$preview.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)
$preview.Dispose()

$diag = $w * 1.308
$safeDiag = $C * 0.611
Write-Output ("占宽比例 {0:P0} -> 宽 {1:N0}px, 高 {2:N0}px" -f $WidthRatio, $w, $h)
Write-Output ("包围盒对角线 {0:N0}px, 安全圆直径 {1:N0}px, {2}" -f $diag, $safeDiag, $(if ($diag -le $safeDiag) { "在安全区内" } else { "略微超出(仅在尖角处)" }))
Write-Output "已输出 5 个密度 + 预览: $previewPath"

$canvas.Dispose(); $img.Dispose()
