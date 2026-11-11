# 从源图生成自适应图标的前景层 PNG（白底转透明，可选加粗线条）
#
# 用法:
#   pwsh -File tools/make-icon.ps1                        # 复现线上图标
#   pwsh -File tools/make-icon.ps1 -WidthRatio 0.47       # 缩小占比
#   pwsh -File tools/make-icon.ps1 -StrokeRadius 3        # 线条更粗
#
# 参数说明:
#   -WidthRatio    蜂鸟占画布宽度的比例
#   -StrokeRadius  线条加粗半径（432 画布上的像素）。0 = 保持原图线宽
#   -AlphaGain     alpha 增益, 让抗锯齿边缘更实, 视觉上更"实心"而不动几何
#   -ProtectSource 需要保持原图粗细的部件, 格式 @(源图X, 源图Y) —— 作为种子点
#                  脚本会从种子点泛洪出整个连通域, 该域内一律还原原图（不加粗、不加增益）
#                  默认保护眼睛与腮红: 它们本来就是实心小色块, 加粗只会糊成一团
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
    [int]$StrokeRadius = 2,
    [double]$AlphaGain = 1.25,
    [string]$Source = "$PSScriptRoot\..\docs\mockups\app-icon-source.png",
    [string]$ResDir = "$PSScriptRoot\..\app\src\main\res",
    [int]$ContentLeft = 54,
    [int]$ContentTop = 82,
    [int]$ContentWidth = 396,
    [int]$ContentHeight = 334,
    # 种子点: 眼睛 (328,156) 与腮红 (305,178), 取源图坐标
    [double[][]]$ProtectSource = @(
        @(328, 156),
        @(305, 178)
    ),
    [switch]$PreviewOnly
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { throw "源图不存在: $Source" }

$C = 432
$bbox = New-Object System.Drawing.Rectangle($ContentLeft, $ContentTop, $ContentWidth, $ContentHeight)

function Build-Icon {
    param([int]$radius, [double]$gain, $protect)

    $img = [System.Drawing.Bitmap]::FromFile($Source)
    $canvas = New-Object System.Drawing.Bitmap($C, $C, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.Clear([System.Drawing.Color]::White)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

    $w = [double]$C * $WidthRatio
    $h = $w * ($bbox.Height / $bbox.Width)
    $destX = [int](($C - $w) / 2)
    $destY = [int](($C - $h) / 2)
    $scale = $w / $bbox.Width
    $dest = New-Object System.Drawing.Rectangle($destX, $destY, [int]$w, [int]$h)
    $g.DrawImage($img, $dest, $bbox.X, $bbox.Y, $bbox.Width, $bbox.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    $img.Dispose()

    # 亮度 -> alpha（raw 保留原图, gained 施加增益）
    $rect = New-Object System.Drawing.Rectangle(0, 0, $C, $C)
    $data = $canvas.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $bytes = New-Object byte[] ($stride * $C)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)

    $raw = New-Object byte[] ($C * $C)
    $alpha = New-Object byte[] ($C * $C)
    for ($y = 0; $y -lt $C; $y++) {
        $row = $y * $stride
        for ($x = 0; $x -lt $C; $x++) {
            $i = $row + $x * 4
            $lum = 0.114 * $bytes[$i] + 0.587 * $bytes[$i + 1] + 0.299 * $bytes[$i + 2]
            $a0 = [int](255 - $lum)
            if ($a0 -gt 255) { $a0 = 255 }; if ($a0 -lt 0) { $a0 = 0 }
            $raw[$y * $C + $x] = [byte]$a0
            $a1 = [int]($a0 * $gain)
            if ($a1 -gt 255) { $a1 = 255 }; if ($a1 -lt 0) { $a1 = 0 }
            $alpha[$y * $C + $x] = [byte]$a1
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
        for ($y = 0; $y -lt $C; $y++) {
            for ($x = 0; $x -lt $C; $x++) {
                $lo = [Math]::Max(0, $y - $radius)
                $hi = [Math]::Min($C - 1, $y + $radius)
                $mx = 0
                for ($k = $lo; $k -le $hi; $k++) {
                    $v = $tmp[$k * $C + $x]
                    if ($v -gt $mx) { $mx = $v }
                }
                $alpha[$y * $C + $x] = [byte]$mx
            }
        }
    }

    # 局部保护: 按**连通域**识别指定部件, 还原为原图 alpha（不加粗、不加增益）
    #
    # 为什么不用圆形区域: 圆形边界会横切相邻笔画（实测切到了睫毛）,
    # 圆内是原图细线、圆外是加粗线, 接缝在放大后非常明显。
    # 连通域掩膜是贴着笔画走的, 边界落在空白处, 不会有接缝。
    if ($protect.Count -gt 0) {
        # 阈值取 8 而不是 128: 抗锯齿边缘的 alpha 在 1~127 之间,
        # 用 128 会把这一圈排除在掩膜外, 膨胀后就在部件外圈留下一道虚线接缝。
        $solid = New-Object 'bool[]' ($C * $C)
        for ($i = 0; $i -lt ($C * $C); $i++) { $solid[$i] = ($raw[$i] -gt 8) }

        $mask = New-Object 'bool[]' ($C * $C)
        foreach ($reg in $protect) {
            $sx = $destX + ([double]$reg[0] - $bbox.X) * $scale
            $sy = $destY + ([double]$reg[1] - $bbox.Y) * $scale

            # 就近找一个实心像素当种子（眼睛中心有白色高光, 不能直接用中心点）
            $seed = -1
            for ($rr = 0; $rr -le 14 -and $seed -lt 0; $rr++) {
                for ($dy = -$rr; $dy -le $rr -and $seed -lt 0; $dy++) {
                    for ($dx = -$rr; $dx -le $rr -and $seed -lt 0; $dx++) {
                        $px = [int]$sx + $dx; $py = [int]$sy + $dy
                        if ($px -ge 0 -and $px -lt $C -and $py -ge 0 -and $py -lt $C) {
                            if ($solid[$py * $C + $px]) { $seed = $py * $C + $px }
                        }
                    }
                }
            }
            if ($seed -lt 0) { continue }

            # 泛洪填充整个连通域
            $seen = New-Object 'bool[]' ($C * $C)
            $stack = New-Object System.Collections.Stack
            $stack.Push($seed)
            $seen[$seed] = $true
            while ($stack.Count -gt 0) {
                $p = [int]$stack.Pop()
                $mask[$p] = $true
                $px = $p % $C
                $py = [int](($p - $px) / $C)
                foreach ($d in @(1, -1, $C, -$C)) {
                    $np = $p + $d
                    if ($np -lt 0 -or $np -ge ($C * $C)) { continue }
                    $npx = $np % $C
                    if ([Math]::Abs($npx - $px) -gt 1) { continue }   # 防止跨行串位
                    if ($solid[$np] -and -not $seen[$np]) {
                        $seen[$np] = $true
                        $stack.Push($np)
                    }
                }
            }
        }

        # 掩膜膨胀, 覆盖加粗外扩的那一圈（多扩 1px 兜住抗锯齿边缘）
        if ($radius -gt 0) {
            $grow = $radius + 1
            $mH = New-Object 'bool[]' ($C * $C)
            for ($y = 0; $y -lt $C; $y++) {
                $b = $y * $C
                for ($x = 0; $x -lt $C; $x++) {
                    $hit = $false
                    $lo = [Math]::Max(0, $x - $grow); $hi = [Math]::Min($C - 1, $x + $grow)
                    for ($k = $lo; $k -le $hi; $k++) { if ($mask[$b + $k]) { $hit = $true; break } }
                    $mH[$b + $x] = $hit
                }
            }
            $mV = New-Object 'bool[]' ($C * $C)
            for ($y = 0; $y -lt $C; $y++) {
                for ($x = 0; $x -lt $C; $x++) {
                    $hit = $false
                    $lo = [Math]::Max(0, $y - $grow); $hi = [Math]::Min($C - 1, $y + $grow)
                    for ($k = $lo; $k -le $hi; $k++) { if ($mH[$k * $C + $x]) { $hit = $true; break } }
                    $mV[$y * $C + $x] = $hit
                }
            }
            $mask = $mV
        }

        for ($i = 0; $i -lt ($C * $C); $i++) { if ($mask[$i]) { $alpha[$i] = $raw[$i] } }
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

$result = Build-Icon -radius $StrokeRadius -gain $AlphaGain -protect $ProtectSource

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
    Write-Output "已输出 5 个密度 (StrokeRadius=$StrokeRadius, AlphaGain=$AlphaGain, 保护区域=$($ProtectSource.Count) 处)"
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
