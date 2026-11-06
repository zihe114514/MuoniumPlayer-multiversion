param([Parameter(Mandatory=$true)][string[]]$Classes, [string]$Filter)
$cp = (Get-Content 'C:\MUSIC\1.20.1\muoniumplayer\build\compile-classpath.txt' -Raw).Trim()
$javap = 'C:\Program Files\Java\jdk-17\bin\javap.exe'
foreach ($c in $Classes) {
  "===== $c ====="
  $out = & $javap -classpath $cp $c 2>&1
  if ($Filter) { $out | Select-String -Pattern $Filter } else { $out }
}
