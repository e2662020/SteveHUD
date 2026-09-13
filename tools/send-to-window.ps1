# Sends a line of text to a window, using the OS input queue rather than the
# computer-use broker.
#
# Why this exists: the computer-use broker on this machine cannot synthesize raw
# input (it reports `window-scoped raw input requires native activateWindow
# support`), and Minecraft draws its entire UI on an OpenGL surface that exposes
# no accessibility elements — the window tree contains only the title bar. So
# neither the semantic nor the coordinate path can reach the game. This script
# goes around the broker by asking Windows itself to focus the window and post
# keystrokes.
#
# Sending input to a game whose screen cannot be read is inherently blind, so the
# caller should always pair this with a command whose effect is observable
# somewhere else (a server log line, for example) to confirm the input arrived.
#
# Usage:
#   powershell -NoProfile -File send-to-window.ps1 -TitlePart Minecraft -Keys "/stevehud resend~"
#
# The ~ character is SendKeys' notation for Enter.

param(
    [Parameter(Mandatory = $true)][string]$TitlePart,
    [Parameter(Mandatory = $true)][string]$Keys,
    [int]$FocusDelayMs = 900
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WindowFocus {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr pid);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
}
"@

$target = Get-Process |
    Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "*$TitlePart*" } |
    Select-Object -First 1

if (-not $target) {
    Write-Output "RESULT: NO_WINDOW (nothing matching '*$TitlePart*' has a main window)"
    exit 1
}

$hwnd = $target.MainWindowHandle
Write-Output ("target: {0} (pid {1}, hwnd {2})" -f $target.MainWindowTitle, $target.Id, $hwnd)

if ([WindowFocus]::IsIconic($hwnd)) {
    [WindowFocus]::ShowWindow($hwnd, 9) | Out-Null   # SW_RESTORE
    Start-Sleep -Milliseconds 400
}

# Windows refuses SetForegroundWindow from a process that does not own the
# foreground, so briefly attach our input queue to the current foreground thread.
$foreground = [WindowFocus]::GetForegroundWindow()
$foregroundThread = [WindowFocus]::GetWindowThreadProcessId($foreground, [IntPtr]::Zero)
$myThread = [WindowFocus]::GetCurrentThreadId()
[WindowFocus]::AttachThreadInput($myThread, $foregroundThread, $true) | Out-Null
[WindowFocus]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds $FocusDelayMs
[WindowFocus]::AttachThreadInput($myThread, $foregroundThread, $false) | Out-Null

$focused = ([WindowFocus]::GetForegroundWindow() -eq $hwnd)
Write-Output ("focused: {0}" -f $focused)
if (-not $focused) {
    Write-Output "RESULT: FOCUS_DENIED (window did not come to the foreground)"
    exit 2
}

[System.Windows.Forms.SendKeys]::SendWait($Keys)
Write-Output "RESULT: SENT"
