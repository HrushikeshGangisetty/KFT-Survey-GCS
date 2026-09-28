<#
.SYNOPSIS
  Serves an RTSP test pattern on this PC, like a SIYI camera, for checking the GCS's video without hardware.

.DESCRIPTION
  Needs MediaMTX (the RTSP server) and FFmpeg (the test pattern) on PATH. Both are dev tools only, never shipped
  with the GCS:
    winget install bluenviron.mediamtx
    winget install Gyan.FFmpeg
  MediaMTX listens on RTSP port 8554; FFmpeg publishes a 1280x720 30 fps test pattern with a running clock to
  rtsp://127.0.0.1:8554/main.264, H.264 without B-frames (-Codec h265 for the H.265 check).

  In the GCS: Fly -> Video -> add rtsp://127.0.0.1:8554/main.264 (desktop), or rtsp://<this PC's IP>:8554/main.264
  from the tablet on the same Wi-Fi (allow MediaMTX through Windows' firewall when asked).
  Reconnect check: press Ctrl+C here (FFmpeg stops, the GCS shows "Video lost", then "Reconnecting"), run the
  script again: the picture comes back by itself.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\video\rtsp-test-stream.ps1
#>
param([ValidateSet("h264", "h265")] [string]$Codec = "h264")
$server = Start-Process mediamtx -PassThru -WindowStyle Minimized
try {
    Start-Sleep -Seconds 1 # let MediaMTX open its port
    $encoder = if ($Codec -eq "h265") { "libx265" } else { "libx264" }
    ffmpeg -hide_banner -re -f lavfi -i "testsrc2=size=1280x720:rate=30" `
        -vf "drawtext=text='%{localtime\:%T}':fontsize=64:fontcolor=white:x=40:y=40:box=1:boxcolor=black" `
        -c:v $encoder -preset ultrafast -tune zerolatency -g 30 -bf 0 -pix_fmt yuv420p `
        -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/main.264
} finally {
    Stop-Process -Id $server.Id -ErrorAction SilentlyContinue
}
