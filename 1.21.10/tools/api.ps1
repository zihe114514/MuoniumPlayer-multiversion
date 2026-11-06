param([Parameter(Mandatory=$true)][string[]]$Classes, [string]$Filter)
$cp = (Get-Content 'C:\MUSIC\1.21.1\build\compile-classpath.txt' -Raw).Trim()
$javap = 'C:\Users\24914\.jdks\ms-21.0.12.1\bin\javap.exe'
foreach ($c in $Classes) {
  "===== $c ====="
  $out = & $javap -classpath $cp $c 2>&1
  if ($Filter) { $out | Select-String -Pattern $Filter } else { $out }
}