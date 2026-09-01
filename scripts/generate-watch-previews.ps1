param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$output = Join-Path $RepositoryRoot 'wear/src/main/res/drawable-nodpi/preview_tide_static.png'
$bitmap = [System.Drawing.Bitmap]::new(466, 466, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$bitmap.SetResolution(96, 96)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$graphics.Clear([System.Drawing.Color]::Transparent)

$background = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#0D0D0D'))
$gold = [System.Drawing.ColorTranslator]::FromHtml('#C9A84C')
$cream = [System.Drawing.ColorTranslator]::FromHtml('#E8E0D0')
$muted = [System.Drawing.ColorTranslator]::FromHtml('#857F6E')
$graphics.FillEllipse($background, 0, 0, 466, 466)
$faceClip = [System.Drawing.Drawing2D.GraphicsPath]::new()
$faceClip.AddEllipse(0, 0, 466, 466)
$graphics.SetClip($faceClip)

$timeFont = [System.Drawing.Font]::new('Segoe UI', 47, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$dateFont = [System.Drawing.Font]::new('Segoe UI', 14, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$centered = [System.Drawing.StringFormat]::new()
$centered.Alignment = [System.Drawing.StringAlignment]::Center
$graphics.DrawString('10:09', $timeFont, [System.Drawing.SolidBrush]::new($cream), [System.Drawing.RectangleF]::new(90, 86, 286, 64), $centered)
$graphics.DrawString('TUE 01 SEP', $dateFont, [System.Drawing.SolidBrush]::new($muted), [System.Drawing.RectangleF]::new(90, 157, 286, 26), $centered)

$wave = [System.Drawing.Drawing2D.GraphicsPath]::new()
$wave.StartFigure()
$wave.AddBezier(14, 283, 58, 221, 90, 209, 116, 224)
$wave.AddBezier(116, 224, 162, 243, 183, 215, 233, 208)
$wave.AddBezier(233, 208, 264, 228, 283, 302, 350, 263)
$wave.AddBezier(350, 263, 396, 224, 428, 247, 452, 283)
$waveFill = $wave.Clone()
$waveFill.AddLine(452, 400, 14, 400)
$waveFill.CloseFigure()
$graphics.FillPath([System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(25, $gold)), $waveFill)
$graphics.DrawPath([System.Drawing.Pen]::new($gold, 2.2), $wave)

$graphics.FillEllipse([System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(26, $gold)), 205, 180, 56, 56)
$graphics.FillEllipse([System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(70, $gold)), 217, 192, 32, 32)
$graphics.FillEllipse([System.Drawing.SolidBrush]::new($gold), 223, 198, 20, 20)
$graphics.DrawEllipse([System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(70, $gold), 3), 221, 396, 24, 24)
$graphics.FillEllipse([System.Drawing.SolidBrush]::new($gold), 226, 401, 14, 14)

$directory = Split-Path -Parent $output
[System.IO.Directory]::CreateDirectory($directory) | Out-Null
$bitmap.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)

$waveFill.Dispose()
$wave.Dispose()
$faceClip.Dispose()
$centered.Dispose()
$dateFont.Dispose()
$timeFont.Dispose()
$background.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

Write-Host "Generated $output"
