# 从源图生成自适应图标的前景层 PNG（白底转透明，可选加粗线条）
#
# 用法:
#   pwsh -File tools/make-icon.ps1                        # 默认参数
#   pwsh -File tools/make-icon.ps1 -WidthRatio 0.47       # 缩小占比
#   pwsh -File tools/make-icon.ps1 -StrokeRadius 3        # 线条更粗
#
# 参数说明:
#   -WidthRatio   蜂鸟占画布宽度的比例
#   -StrokeRadius 线条加粗半径（432 画布上的像素）。0 = 保持原图线宽
#                 实测取 1: 2 会让眼睛与腮红、翅膀羽毛粘连在一起, 失去原图神态
#   -AlphaGain    alpha 增益, 让抗锯齿边缘更实, 视觉上更"实心"而不动几何
#
# 安全区说明:
#   自适应图标画布 108dp, 保证可见区域是以中心为圆心的 66dp 圆。
#   本图内容是斜向构图（翅膀在左上、尾羽在左下、喙在右），
#   所以真正决定是否被裁的是**包围盒对角线**, 不是宽度:
#       对角线 = 宽度 * sqrt(1 + (高/宽)^2) = 宽度 * 1.308
#   要让对角线落在 66dp 圆内: 宽度 <= 0.611 * 432 / 1.308 ≈ 202px ≈ 画布的 46.8%
#
# 加粗原理:
#   对 alpha 通道做**形态学膨胀**（分离式最大值滤波）, 而不是模糊。
#   模糊会让线条变虚, 膨胀是让线条真正变宽且边缘依然锐利。

param(
    [double]$WidthRatio = 0.50,
    [int]$StrokeRadius = 1,
    [double]$AlphaGain = 1.6,
    [string]$Source = "$PSScriptRoot\..\docs\mockups\app-icon-source.png",
    [string]$ResDir = "$PSScriptRoot\..\app\src\main\res",
    [int]$ContentLeft = 54,
    [int]$ContentTop = 82,
    [int]$ContentWidth = 396,
    [int]$ContentHeight = 334,
    [switch]$PreviewOnly
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { throw "源图不存在: $Source" }

$C = 432
$bbox = New-Object System.Drawing.Rectangle($ContentLeft, $ContentTop, $ContentWidth, $ContentHeight)

function Build-Icon {
    param([int]$radius, [double]$gain)

    $img = [System.Drawing.Bitmap]::FromFile($Source)
    $canvas = New-Object System.Drawing.Bitmap($C, $C, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.Clear([System.Drawing.Color]::White)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

    $w = [double]$C * $WidthRatio
    $h = $w * ($bbox.Height / $bbox.Width)
    $dest = New-Object System.Drawing.Rectangle([int](($C - $w) / 2), [int](($C - $h) / 2), [int]$w, [int]$h)
    $g.DrawImage($img, $dest, $bbox.X, $bbox.Y, $bbox.Width, $bbox.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    $img.Dispose()

    # 亮度 -> alpha
    $rect = New-Object System.Drawing.Rectangle(0, 0, $C, $C)
    $data = $canvas.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $bytes = New-Object byte[] ($stride * $C)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)

    $alpha = New-Object byte[] ($C * $C)
    for ($y = 0; $y -lt $C; $y++) {
        $row = $y * $stride
        for ($x = 0; $x -lt $C; $x++) {
            $i = $row + $x * 4
            $lum = 0.114 * $bytes[$i] + 0.587 * $bytes[$i + 1] + 0.299 * $bytes[$i + 2]
            $a = [int]((255 - $lum) * $gain)
            if ($a -gt 255) { $a = 255 }
            if ($a -lt 0) { $a = 0 }
            $alpha[$y * $C + $x] = [byte]$a
        }
    }

    # 形态学膨胀: 分离式最大值滤波（先横向再纵向）
    if ($radius -gt 0) {
        $tmp = New-Object byte[] ($C * $C)
        for ($y = 0; $y -lt $C; $y++) {
            $base = $y * $C
            for ($x = 0; $x -lt $C; $x++) {
                $lo = [Math]::Max(0, $x - $radius)
                $hi = [Math]::Min($C - 1, $x + $radius)
                $mx = 0
                for ($k = $lo; $k -le $hi; $k++) {
                    $v = $alpha[$base + $k]
                    if ($v -gt $mx) { $mx = $v }
                }
                $tmp[$base + $x] = [byte]$mx
            }
        }
        $alpha2 = New-Object byte[] ($C * $C)
        for ($y = 0; $y -lt $C; $y++) {
            for ($x = 0; $x -lt $C; $x++) {
                $lo = [Math]::Max(0, $y - $radius)
                $hi = [Math]::Min($C - 1, $y + $radius)
                $mx = 0
                for ($k = $lo; $k -le $hi; $k++) {
                    $v = $tmp[$k * $C + $x]
                    if ($v -gt $mx) { $mx = $v }
                }
                $alpha2[$y * $C + $x] = [byte]$mx
            }
        }
        $alpha = $alpha2
    }

    # 写回: 纯黑 + 计算出的 alpha
    for ($y = 0; $y -lt $C; $y++) {
        $row = $y * $stride
        for ($x = 0; $x -lt $C; $x++) {
            $i = $row + $x * 4
            $bytes[$i] = 0; $bytes[$i + 1] = 0; $bytes[$i + 2] = 0
            $bytes[$i + 3] = $alpha[$y * $C + $x]
        }
    }
    [System.Runtime.InteropServices.Marshal]::Copy($bytes, 0, $data.Scan0, $bytes.Length)
    $canvas.UnlockBits($data)
    return $canvas
}

$result = Build-Icon -radius $StrokeRadius -gain $AlphaGain

if (-not $PreviewOnly) {
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
        $bmp = New-Object System.Drawing.Bitmap($result, $t.size, $t.size)
        $bmp.Save((Join-Path $dir "ic_launcher_bird.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    }
    Write-Output "已输出 5 个密度 (StrokeRadius=$StrokeRadius, AlphaGain=$AlphaGain)"
}

# 预览: 白底 + 安全区参考圆
$preview = New-Object System.Drawing.Bitmap($C, $C)
$pg = [System.Drawing.Graphics]::FromImage($preview)
$pg.Clear([System.Drawing.Color]::White)
$pg.DrawImage($result, 0, 0)
$pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(80, 255, 0, 0), 1.5)
$safe = [int]($C * 0.611)
$pg.DrawEllipse($pen, [int](($C - $safe) / 2), [int](($C - $safe) / 2), $safe, $safe)
$pen.Dispose(); $pg.Dispose()
$previewPath = Join-Path $PSScriptRoot "..\.tooling\shots\icon_preview.png"
New-Item -ItemType Directory -Force -Path (Split-Path $previewPath) | Out-Null
$preview.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)
$preview.Dispose()

$w2 = [double]$C * $WidthRatio
Write-Output ("占宽 {0:P0} -> 宽 {1:N0}px, 包围盒对角线 {2:N0}px (安全圆 {3:N0}px)" -f $WidthRatio, $w2, ($w2 * 1.308), ($C * 0.611))
Write-Output "预览: $previewPath"
$result.Dispose()
