# Live video from the VisionNav Pi's two IMX296 cameras

The GCS plays any RTSP URL (ADR-004), so nothing new is decoded on our side. The Pi publishes each camera as its own
**H.264 RTSP path** through MediaMTX; the GCS has two presets for them:

| GCS preset (Fly → Video) | URL |
|---|---|
| VisionNav cam0 | `rtsp://visionnav-kft.local:8554/cam0` |
| VisionNav cam1 | `rtsp://visionnav-kft.local:8554/cam1` |

If `.local` doesn't resolve (common on Android and on some Wi-Fi), use **Video → add RTSP** with the Pi's IP, e.g.
`rtsp://192.168.99.190:8554/cam0` (the IP the router gave it on 22 Oct; check yours).

**Facts from the GNSS-Denied repo** (`Docs/PI_FC_BRINGUP_PROCEDURE.md`, `Docs/runs/2026-10-22_first_hardware.md`):
Pi 5, hostname `VisionNav-KFT`, two CAM-IMX296Color-GS (colour, 1456x1088, 60 fps max), `camera_auto_detect=0` and
`dtoverlay=imx296,cam0` / `cam1` after `[all]` in `/boot/firmware/config.txt`, libcamera 0.7.2, rpicam-apps 1.13.0.
Everything below the line is **not yet run on hardware**: treat it as the first thing to test.

## Three things to know first

1. **One process per camera.** libcamera lets only one program open a camera. The recorder (`platform/recorder`) and
   this stream cannot both run on the same camera. Use the stream for bench/preview; stop it before recording.
   Streaming *during* recording needs the recorder to tee frames to an encoder (a change in the Pi repo, not here).
2. **Pi 5 has no hardware H.264 encoder.** The stream is software x264, using Pi CPU the VIO also wants
   (AGENTS.md: preview has the lowest priority). So the stream is deliberately small: 728x544 at 15 fps.
3. **H.264 only.** The Android tablet can't play H.265 over RTSP (ADR-004).

## Pi setup (once)

```bash
sudo apt update && sudo apt install -y ffmpeg
# MediaMTX: download the linux_arm64 release from github.com/bluenviron/mediamtx/releases, then:
mkdir -p ~/mediamtx && tar -xzf mediamtx_*_linux_arm64.tar.gz -C ~/mediamtx
```

Check the cameras first (expect two `imx296`, 1456x1088):

```bash
rpicam-hello --list-cameras
```

## Run it by hand (to prove it works)

Terminal 1: the RTSP server (the default config accepts publishers from localhost and viewers from anywhere):

```bash
~/mediamtx/mediamtx
```

Terminal 2 and 3, one per camera (`--camera 0` → path `cam0`, `--camera 1` → `cam1`):

```bash
rpicam-vid --camera 0 -t 0 -n --width 728 --height 544 --framerate 15 \
  --codec libav --libav-video-codec libx264 --libav-format h264 \
  --bitrate 1500000 --intra 15 -o - \
| ffmpeg -loglevel warning -fflags nobuffer -f h264 -i - -c copy -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/cam0
```

(Repeat with `--camera 1` and `/cam1`.) `--intra 15` is a keyframe every second, so a GCS that connects late or
reconnects gets a picture within a second. `-c copy` means ffmpeg only repackages; x264 runs once, inside rpicam-vid.

Test on the Pi first: `ffplay rtsp://127.0.0.1:8554/cam0`. Then from the laptop: VLC or the GCS preset.
Watch CPU with `top`: if one core per stream is saturated, lower to `--framerate 10` or `--width 640 --height 480`.

## Run it on boot (once it works)

`/etc/systemd/system/vn-video@.service`, started as `vn-video@0` and `vn-video@1`:

```ini
[Unit]
Description=VisionNav camera %i RTSP stream
After=network-online.target mediamtx.service
Wants=network-online.target

[Service]
User=<pi-user>
ExecStart=/bin/bash -c 'rpicam-vid --camera %i -t 0 -n --width 728 --height 544 --framerate 15 --codec libav --libav-video-codec libx264 --libav-format h264 --bitrate 1500000 --intra 15 -o - | ffmpeg -loglevel warning -fflags nobuffer -f h264 -i - -c copy -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/cam%i'
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
```

Run MediaMTX the same way (a `mediamtx.service` with `ExecStart=/home/<pi-user>/mediamtx/mediamtx`), then
`sudo systemctl enable --now mediamtx vn-video@0 vn-video@1`. Before recording: `sudo systemctl stop vn-video@0 vn-video@1`.

## What to check and where it fails

| Symptom in the GCS | Likely cause |
|---|---|
| "connecting" forever | Laptop and Pi on different networks; `.local` not resolving (use the IP); firewall on 8554 |
| Picture then "lost" every few seconds | Pi CPU saturated (lower size/fps) or weak Wi-Fi |
| Wrong colours (blue/red swapped) | `rpicam-vid` BGR/RGB ordering; check with `ffplay` on the Pi before blaming the GCS |
| `rpicam-vid` says the camera is busy | The recorder or another stream owns it |
| Android says "set the camera to H.264" | The encoder isn't libx264 H.264 (check `--libav-video-codec`) |

Latency is the sum of x264 (`ultrafast`/zerolatency is rpicam-vid's libav default for live use), the network, and the
GCS (<50 ms on desktop per the SIYI check). Measure it with the statistics button, as in `checklists/hardware-session-1.md` §3.

## Not done (and why)

- **Both cameras in one picture** (side-by-side): two paths are simpler and the GCS can swap between them. Add only if
  the pilot needs both at once; the PiP window shows one source today.
- **Stream during recording**: needs the recorder to feed an encoder (see point 1).
- **Authentication on MediaMTX**: bench network only. Add `readUser`/`readPass` in `mediamtx.yml` before flying on a shared network.
