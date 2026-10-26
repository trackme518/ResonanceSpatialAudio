param([string]$keep = "")
$ErrorActionPreference = "Continue"
$p7 = "C:\Users\leischner\Documents\Processing-4-5-7"
$javac = "$p7\app\resources\jdk\bin\javac.exe"
$core = (Get-ChildItem "$p7\app\core-4.5.7-*.jar" | Select-Object -First 1).FullName
$sound = "C:\Users\leischner\Documents\Processing\libraries\sound\library"
$cp = "$core;$sound\sound.jar;$sound\jsyn-17.1.0.jar;ResonanceAudio\library\ResonanceAudio.jar"
New-Item -ItemType Directory -Force -Path out\excheck, out\exsrc | Out-Null
$fail = 0
Get-ChildItem ResonanceAudio\examples -Directory | ForEach-Object {
  $n = $_.Name
  $raw = Get-Content -Raw "$($_.FullName)\$n.pde"
  # extract imports (PDE hoists them above the class)
  $imports = [regex]::Matches($raw, "(?m)^import\s+[^;]+;") | ForEach-Object { $_.Value }
  $body = [regex]::Replace($raw, "(?m)^import\s+[^;]+;\s*", "")
  # PDE hex color literal -> int literal
  $body = [regex]::Replace($body, "#([0-9A-Fa-f]{6})", "0x`$1")
  # PDE auto-appends 'f' to floating point literals
  $body = [regex]::Replace($body, "(?<![\w.])(\d+\.\d+)(?![\w.f])", "`$1f")
  $body = [regex]::Replace($body, "(?<![\w.])(\d+\.\d+)(?![\w.f])", "`$1f")
  # PDE lifecycle/event methods are public in PApplet
  $body = [regex]::Replace($body, "(?m)^void (setup|draw|mousePressed|mouseReleased|mouseClicked|mouseMoved|mouseDragged|mouseWheel|keyPressed|keyReleased|keyTyped|dispose|preinit|init|settings)\(", "public void `$1(")
  $wrapped = ($imports -join "`r`n") + "`r`nimport processing.core.PApplet;`r`n" +
             "public class $n extends PApplet {`r`n" + $body + "`r`n}"
  $src = "out\exsrc\$n.java"
  [System.IO.File]::WriteAllText((Join-Path (Get-Location) $src), $wrapped)
  $out = & $javac -nowarn -encoding UTF-8 --release 11 -cp $cp -d out\excheck $src 2>&1 | Out-String
  if ($LASTEXITCODE -ne 0) { $fail++; Write-Output "FAILED: $n"; Write-Output $out }
  else { Write-Output "OK: $n" }
  if ($keep) { Copy-Item $src "examples_checked\$n.java" -Force -ErrorAction SilentlyContinue }
}
Write-Output "FAILS=$fail"
