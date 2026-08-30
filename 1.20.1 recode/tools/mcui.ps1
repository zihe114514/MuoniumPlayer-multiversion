param(
  [int]$TargetPid,
  [int[]]$ClickX = @(),
  [int[]]$ClickY = @(),
  [string]$Shot = '',
  [string[]]$Keys = @(),
  [int]$MiddleClicks = 0,
  [int]$WaitMs = 1200
)
Add-Type -Namespace W -Name U -MemberDefinition @"
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern IntPtr SetActiveWindow(IntPtr h);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
[DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
[DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, int extra);
[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, int extra);
[DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint mapType);
public struct RECT { public int Left, Top, Right, Bottom; }
public struct POINT { public int X, Y; }
"@
$p = Get-Process -Id $TargetPid -ErrorAction Stop
[W.U]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
for ($try = 0; $try -lt 15 -and [W.U]::GetForegroundWindow() -ne $p.MainWindowHandle; $try++) {
  [W.U]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
  Start-Sleep -Milliseconds 250
}
Start-Sleep -Milliseconds 600
if ([W.U]::GetForegroundWindow() -ne $p.MainWindowHandle) { Write-Error "target window is not foreground; screenshot/clicks would hit the wrong window"; exit 1 }
$cr = New-Object W.U+RECT
[W.U]::GetClientRect($p.MainWindowHandle, [ref]$cr) | Out-Null
$origin = New-Object W.U+POINT
[W.U]::ClientToScreen($p.MainWindowHandle, [ref]$origin) | Out-Null

for ($i = 0; $i -lt $ClickX.Count; $i++) {
  [W.U]::SetCursorPos($origin.X + $ClickX[$i], $origin.Y + $ClickY[$i]) | Out-Null
  Start-Sleep -Milliseconds 250
  [W.U]::mouse_event(0x0002, 0, 0, 0, 0)   # LEFTDOWN
  Start-Sleep -Milliseconds 90
  [W.U]::mouse_event(0x0004, 0, 0, 0, 0)   # LEFTUP
  Start-Sleep -Milliseconds $WaitMs
}

# 中键点击（用来触发绑定到鼠标中键的 mod 按键）
for ($i = 0; $i -lt $MiddleClicks; $i++) {
  # 中键必须先把光标放到目标窗口里：mouse_event 是按光标位置投递的，
  # 不定位的话点击会落到脚本自己的终端窗口上。
  [W.U]::SetCursorPos($origin.X + [int]($cr.Right / 2), $origin.Y + [int]($cr.Bottom / 2)) | Out-Null
  Start-Sleep -Milliseconds 200
  [W.U]::mouse_event(0x0020, 0, 0, 0, 0)   # MIDDLEDOWN
  Start-Sleep -Milliseconds 90
  [W.U]::mouse_event(0x0040, 0, 0, 0, 0)   # MIDDLEUP
  Start-Sleep -Milliseconds $WaitMs
}

# 按键：先点击（如果有），再依次敲键。名字见 $vk 映射表。
$vk = @{ 'H'=0x48; 'ESC'=0x1B; 'ENTER'=0x0D; 'TAB'=0x09; 'E'=0x45; 'F3'=0x72; 'F11'=0x7A; 'SPACE'=0x20;
         'UP'=0x26; 'DOWN'=0x28; 'LEFT'=0x25; 'RIGHT'=0x27; 'T'=0x54; 'L'=0x4C; 'P'=0x50; 'RSHIFT'=0xA1; 'LSHIFT'=0xA0; 'PRIOR'=0x21; 'NEXT'=0x22; 'ADD'=0x6B; 'SUBTRACT'=0x6D; 'MULTIPLY'=0x6A; 'J'=0x4A; 'K'=0x4B; 'M'=0x4D; 'N'=0x4E; 'B'=0x42; 'F'=0x46; 'W'=0x57; 'A'=0x41; 'S'=0x53; 'D'=0x44 }
foreach ($k in $Keys) {
  $code = $vk[$k.ToUpper()]
  if ($null -eq $code) { Write-Error "unknown key $k"; continue }
  # 每次按键前重新抢焦点：终端窗口会把键盘焦点抢回去，keybd_event 只会送给前台窗口。
  for ($try = 0; $try -lt 10 -and [W.U]::GetForegroundWindow() -ne $p.MainWindowHandle; $try++) {
    [W.U]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 200
  }
  if ([W.U]::GetForegroundWindow() -ne $p.MainWindowHandle) { Write-Error "cannot focus target window"; break }
  [W.U]::keybd_event([byte]$code, 0, 0, 0)
  Start-Sleep -Milliseconds 80
  [W.U]::keybd_event([byte]$code, 0, 2, 0)
  Start-Sleep -Milliseconds $WaitMs
}

if ($Shot -ne '') {
  Start-Sleep -Milliseconds 400
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object System.Drawing.Bitmap($cr.Right, $cr.Bottom)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($origin.X, $origin.Y, 0, 0, (New-Object System.Drawing.Size($cr.Right, $cr.Bottom)))
  $bmp.Save($Shot, [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
  "saved $Shot  client=$($cr.Right)x$($cr.Bottom)"
}
