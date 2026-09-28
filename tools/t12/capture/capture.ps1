<#
.SYNOPSIS
  Records the Skydroid T12's raw serial traffic (its answer to every poll) to a capture file for the replay test.

.DESCRIPTION
  Plug the T12 into the laptop (USB, the data port) with the air unit powered and linked. Close the GCS first: the
  T12 has one serial port and only one program can hold it. The recorder finds the T12 by its USB IDs (CP2102,
  10C4:EA60), turns its video on the way the app does, and writes every poll answer to the file for -Seconds.
  Then copy the file into tools\t12\captures\ and run `gradlew.bat check`: T12VideoPipelineTest replays every
  capture there through the app's own framing, H.264 splitting and FFmpeg decoder.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\t12\capture\capture.ps1 -Out tools\t12\captures\bench-1.t12cap -Seconds 20
#>
param(
    [string]$Out = "tools\t12\captures\t12-$(Get-Date -Format yyyyMMdd-HHmmss).t12cap",
    [int]$Seconds = 20
)
$root = Resolve-Path "$PSScriptRoot\..\..\.."
$file = [System.IO.Path]::GetFullPath((Join-Path $root $Out))
& "$root\gradlew.bat" -p $root :core:mavlink:t12Capture --args="`"$file`" $Seconds"
