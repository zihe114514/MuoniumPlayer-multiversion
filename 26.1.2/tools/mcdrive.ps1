param(
  [int]$TargetPid,
  [int[]]$ClickX = @(),
  [int[]]$ClickY = @(),
  [string]$Shot = '',
  [string[]]$Keys = @(),
  [string]$Text = '',
  [int]$WaitMs = 1200,
  [switch]$NoFocusClick
)
Add-Type -Namespace WD -Name U -MemberDefinition @"
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
[DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
[DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, int extra);
[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, int extra);
[DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint mapType);
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
public struct RECT { public int Left, Top, Right, Bottom; }
public struct POINT { public int X, Y; }
"@
$p = Get-Process -Id $TargetPid -ErrorAction Stop
$h = $p.MainWindowHandle
[WD.U]::ShowWindow($h, 9) | Out-Null
# Raise above the current foreground window so the focus click actually lands on it.
# HWND_TOPMOST then back to HWND_NOTOPMOST leaves it at the top of the normal z-order.
[WD.U]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x0013) | Out-Null   # TOPMOST | NOMOVE|NOSIZE|NOACTIVATE
Start-Sleep -Milliseconds 200
[WD.U]::SetWindowPos($h, [IntPtr](-2), 0, 0, 0, 0, 0x0013) | Out-Null   # NOTOPMOST
Start-Sleep -Milliseconds 400
$cr = New-Object WD.U+RECT
[WD.U]::GetClientRect($h, [ref]$cr) | Out-Null
$origin = New-Object WD.U+POINT
[WD.U]::ClientToScreen($h, [ref]$origin) | Out-Null

function Click([int]$x, [int]$y) {
  [WD.U]::SetCursorPos($origin.X + $x, $origin.Y + $y) | Out-Null
  Start-Sleep -Milliseconds 220
  [WD.U]::mouse_event(0x0002, 0, 0, 0, 0)
  Start-Sleep -Milliseconds 90
  [WD.U]::mouse_event(0x0004, 0, 0, 0, 0)
}

# Clicking inside the window gives it keyboard focus without SetForegroundWindow.
if (-not $NoFocusClick) {
  # click on a harmless spot: 2px inside top-left of the client area
  Click 3 3
  Start-Sleep -Milliseconds 700
}
if ([WD.U]::GetForegroundWindow() -ne $h) { Write-Error "still not foreground after focus click"; exit 1 }

for ($i = 0; $i -lt $ClickX.Count; $i++) {
  Click $ClickX[$i] $ClickY[$i]
  Start-Sleep -Milliseconds $WaitMs
}

$vk = @{ 'H'=0x48; 'ESC'=0x1B; 'ENTER'=0x0D; 'TAB'=0x09; 'E'=0x45; 'F2'=0x71; 'F3'=0x72; 'F11'=0x7A; 'SPACE'=0x20;
         'UP'=0x26; 'DOWN'=0x28; 'LEFT'=0x25; 'RIGHT'=0x27; 'T'=0x54; 'L'=0x4C; 'P'=0x50; 'RSHIFT'=0xA1; 'LSHIFT'=0xA0;
         'PRIOR'=0x21; 'NEXT'=0x22; 'ADD'=0x6B; 'SUBTRACT'=0x6D; 'MULTIPLY'=0x6A; 'J'=0x4A; 'K'=0x4B; 'M'=0x4D;
         'N'=0x4E; 'B'=0x42; 'F'=0x46; 'W'=0x57; 'A'=0x41; 'S'=0x53; 'D'=0x44; 'F1'=0x70 }
foreach ($k in $Keys) {
  $code = $vk[$k.ToUpper()]
  if ($null -eq $code) { Write-Error "unknown key $k"; continue }
  if ([WD.U]::GetForegroundWindow() -ne $h) { Write-Error "lost focus before key $k"; exit 1 }
  $scan = [byte]([WD.U]::MapVirtualKey([uint32]$code, 0))
  [WD.U]::keybd_event([byte]$code, $scan, 0, 0)
  Start-Sleep -Milliseconds 90
  [WD.U]::keybd_event([byte]$code, $scan, 2, 0)
  Start-Sleep -Milliseconds $WaitMs
}

# Type a string (e.g. a chat command). GLFW takes chars from real key events and SendKeys
# goes through keybd_event, so this works. Used for things like /time set day.
if ($Text -ne '') {
  if ([WD.U]::GetForegroundWindow() -ne $h) { Write-Error "lost focus before text"; exit 1 }
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.SendKeys]::SendWait($Text)
  Start-Sleep -Milliseconds $WaitMs
}

if ($Shot -ne '') {
  Start-Sleep -Milliseconds 400
  if ([WD.U]::GetForegroundWindow() -ne $h) { Write-Error "target not foreground at screenshot time"; exit 1 }
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object System.Drawing.Bitmap($cr.Right, $cr.Bottom)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($origin.X, $origin.Y, 0, 0, (New-Object System.Drawing.Size($cr.Right, $cr.Bottom)))
  $g.Dispose()
  $full = $Shot
  if (-not [System.IO.Path]::IsPathRooted($full)) { $full = Join-Path (Get-Location).Path $Shot }
  $bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Output "saved $full ($($cr.Right)x$($cr.Bottom))"
}
